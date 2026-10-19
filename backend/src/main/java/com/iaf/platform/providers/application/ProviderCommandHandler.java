package com.iaf.platform.providers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Hashes;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.common.redaction.Redactor;
import com.iaf.platform.common.redaction.SecretValues;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.common.schema.Schemas.Mismatch;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.providers.domain.ConnectionResult;
import com.iaf.platform.providers.domain.OpenApiImporter;
import com.iaf.platform.providers.domain.ProviderChecks;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.domain.ProviderDefinition.Auth;
import com.iaf.platform.providers.domain.ProviderDefinition.GitRef;
import com.iaf.platform.providers.domain.ProviderDefinition.SpecSource;
import com.iaf.platform.providers.domain.ProviderReview;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TextDiff;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.providers.ports.inbound.ProviderCommands;
import com.iaf.platform.providers.ports.outbound.GitSourcePort;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.providers.ports.outbound.ReviewCall;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The Add provider wizard's backend and the admin edits. Publishing, a spec refresh, an override
 * and an SLO edit each open a change request (one open at a time per provider); the provider is
 * saved when the workflows slice merges it and publishes {@code change-request.merged}, which this
 * handler listens to.
 *
 * <p>A merged version with the {@code canary} strategy is kept beside the current one and rolled
 * out by a provider release (the releases slice); it becomes current on {@code release.promoted}.
 * Edits made while it rolls out build on it.
 *
 * <p>A {@code vault:} reference only goes outward (to an endpoint, to a repository) once the
 * caller may use the credential ({@link CredentialUse}); the secret is never resolved before that.
 * What the wizard keeps (the review evidence, the sample written into the draft) is redacted
 * through {@link Redactor} with the secrets the platform resolved ({@link SecretValues}); only the
 * live answer of a Try it goes back to the caller as it came.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ProviderCommandHandler implements ProviderCommands {

    public static final String MERGED_EVENT = "change-request.merged";
    public static final String PUBLISHED_EVENT = "provider.published";
    public static final String CHANGE_REQUESTED_EVENT = "provider.change-requested";
    /** The wizard's steps on the audit trail: what was imported (by hash), tested and tried, by whom; never a secret or a response body. */
    public static final String SPEC_IMPORTED_EVENT = "provider.spec-imported";
    public static final String CONNECTION_TESTED_EVENT = "provider.connection-tested";
    public static final String TRIED_EVENT = "provider.tried";

    /** What a secret-looking field of a recorded sample becomes in the provider file. */
    public static final String REDACTED = "vault:redacted";

    /** The change-request check of a spec refresh: what was read and what changed. */
    public static final String SPEC_REFRESH = "spec-refresh";

    /** How a provider version rolls out when its change request does not say: like workflow versions, through a canary. */
    static final String DEFAULT_STRATEGY = "canary";
    /** Every call at once: overrides and SLO edits, which change no call, when no canary is running. */
    static final String ALL = "all";
    static final String RELEASE_PROMOTED = "release.promoted";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Set<String> STRATEGIES = Set.of("canary", "all");

    private final ProviderStorePort store;
    private final ChangeRequestPort changeRequests;
    private final ProviderCallPort providerCalls;
    private final AssistantCommands assistant;
    private final HttpProbePort http;
    private final EventBus events;
    private final Clock clock;
    private final ReviewEvidencePort evidence;
    private final GitSourcePort git;
    private final ReleaseQueries releases;
    private final CredentialsPort credentials;
    private final SecretValues secrets;

    @PostConstruct
    void listen() {
        events.register(this::on);
    }

    // ---------------------------------------------------------------- wizard

    @Override
    public Mono<SpecImport> importSpec(ImportSpec command) {
        return CurrentIdentity.current().flatMap(who -> Mono.defer(() -> {
            if (command == null) {
                throw new IllegalArgumentException("type is required");
            }
            String type = command.type() == null || command.type().isBlank() ? "openapi" : command.type().trim();
            if (!"openapi".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("Only OpenAPI specs can be imported; type was " + type);
            }
            boolean hasUrl = command.url() != null && !command.url().isBlank();
            boolean hasContent = command.content() != null && !command.content().isBlank();
            if (command.git() != null) {
                if (hasUrl || hasContent) {
                    throw new IllegalArgumentException("Give the spec's url, its content or its file in Git, one of them");
                }
                GitRef g = requireGitRef(command.git());
                return CredentialUse.guarded(credentials, who, () -> readFromGit(g, g.ref()), g.credential())
                        .map(f -> OpenApiImporter.parse(f.content()).withCommit(f.commit()))
                        .flatMap(spec -> imported(who, spec, "git", null, g));
            }
            if (!hasUrl && !hasContent) {
                throw new IllegalArgumentException("Give the spec's url, paste its content or name its file in Git");
            }
            if (hasContent) {
                return Mono.fromCallable(() -> OpenApiImporter.parse(command.content()))
                        .flatMap(spec -> imported(who, spec, "content", null, null));
            }
            String url = command.url().trim();
            return http.fetch(url)
                    .onErrorMap(e -> !(e instanceof IllegalArgumentException), e -> new IllegalArgumentException("Could not fetch " + url + ": " + reason(e)))
                    .map(OpenApiImporter::parse)
                    .flatMap(spec -> imported(who, spec, "url", url, null));
        }));
    }

    /** The import on the audit trail: where the document came from and its hash, never the document itself. */
    private Mono<SpecImport> imported(Identity who, SpecImport spec, String source, String url, GitRef git) {
        return blocking(() -> {
            ObjectNode data = F.objectNode();
            data.put("source", source);
            if (url != null) {
                data.put("url", url);
            }
            if (git != null) {
                data.put("repo", git.repo());
                if (git.ref() != null) {
                    data.put("ref", git.ref());
                }
                data.put("path", git.path());
                if (git.credential() != null) {
                    data.put("credential", git.credential()); // the reference, never the token
                }
            }
            if (spec.commit() != null) {
                data.put("commit", spec.commit());
            }
            data.put("sha256", spec.sha256());
            if (spec.title() != null) {
                data.put("title", spec.title());
            }
            data.put("operations", spec.operations() == null ? 0 : spec.operations().size());
            data.put("by", who.id());
            events.publish(SPEC_IMPORTED_EVENT, "spec", spec.sha256(), who.id(), data);
            return spec;
        });
    }

    /** A Git location names the repository and the file; a private repository's token is a Vault reference, never the token. */
    static GitRef requireGitRef(GitRef g) {
        if (g.repo() == null || g.repo().isBlank()) {
            throw new IllegalArgumentException("git.repo is required: the repository's https URL, like https://github.com/acme/fleet-api");
        }
        if (g.path() == null || g.path().isBlank()) {
            throw new IllegalArgumentException("git.path is required: the spec's file in the repository, like openapi.yaml");
        }
        if (g.credential() != null && !g.credential().isBlank() && !g.credential().trim().startsWith("vault:")) {
            throw new IllegalArgumentException("git.credential is a Vault reference to a read token, like vault:github/read-token; the token itself never goes through the API");
        }
        return new GitRef(g.repo().trim(), g.ref() == null || g.ref().isBlank() ? null : g.ref().trim(), g.path().trim(),
                g.credential() == null || g.credential().isBlank() ? null : g.credential().trim());
    }

    private Mono<GitSourcePort.GitFile> readFromGit(GitRef g, String ref) {
        return git.file(g.repo(), ref, g.path(), g.credential())
                .onErrorMap(e -> !(e instanceof IllegalArgumentException), e -> new IllegalArgumentException("Could not read " + g.path() + " from " + g.repo() + ": " + reason(e)));
    }

    // ---------------------------------------------------------------- refresh the spec

    /** The document a refresh read, with the commit and ref of a spec read from Git. */
    record LatestSpec(String raw, String commit, String ref) {
    }

    @Override
    public Mono<CommandAccepted> refreshSpec(String name, RefreshSpec command) {
        RefreshSpec c = command == null ? new RefreshSpec(null, null) : command;
        return CurrentIdentity.current().flatMap(who -> candidate(name).flatMap(candidate -> blocking(() -> {
            StoredProvider current = latest(name, candidate);
            ProviderDefinition def = current.definition();
            AccessRules.requireEdit(who, def.owner(), "provider " + name);
            SpecSource source = def.spec().specSource();
            boolean hasSpec = source != null && (source.fromGit() || !blank(source.url()) || !blank(source.content()));
            if (def.type() != ProviderType.OPENAPI || !hasSpec) {
                throw new IllegalArgumentException("Provider " + name + " has no OpenAPI spec to refresh");
            }
            if (source.fromGit()) {
                requireGitRef(source.git());
            }
            if (!blank(c.ref()) && !source.fromGit()) {
                throw new IllegalArgumentException("ref only applies to a spec read from Git; provider " + name + " reads its spec from " + where(source));
            }
            if (!blank(c.content()) && !source.pasted()) {
                throw new IllegalArgumentException("content only replaces a pasted spec; provider " + name + " reads its spec from " + where(source));
            }
            if (source.pasted() && blank(c.content())) {
                throw new IllegalArgumentException("The spec of provider " + name + " was pasted; paste the new document as content");
            }
            requireNoOpenChange(name);
            return current;
        })).flatMap(current -> latestSpec(who, current.definition().spec().specSource(), c)
                .flatMap(latest -> blocking(() -> refreshed(who, current, latest, c)))));
    }

    private Mono<LatestSpec> latestSpec(Identity who, SpecSource source, RefreshSpec c) {
        if (source.fromGit()) {
            GitRef g = requireGitRef(source.git());
            String ref = blank(c.ref()) ? g.ref() : c.ref().trim();
            return CredentialUse.guarded(credentials, who, () -> readFromGit(g, ref), g.credential()).map(f -> new LatestSpec(f.content(), f.commit(), ref));
        }
        if (source.pasted()) {
            return Mono.just(new LatestSpec(c.content(), null, null));
        }
        String url = source.url().trim();
        return http.fetch(url)
                .onErrorMap(e -> !(e instanceof IllegalArgumentException), e -> new IllegalArgumentException("Could not fetch " + url + ": " + reason(e)))
                .map(raw -> new LatestSpec(raw, null, null));
    }

    /**
     * The provider file moved to the new document: the schemas of the actions that call its
     * operations, the overrides the new spec no longer needs dropped (it agrees with the real
     * response), the pin, and the next minor version. Refused when an operation an action calls is gone.
     */
    private CommandAccepted refreshed(Identity who, StoredProvider current, LatestSpec latest, RefreshSpec c) {
        ProviderDefinition def = current.definition();
        SpecSource source = def.spec().specSource();
        String sha = Hashes.sha256(latest.raw());
        if (sha.equalsIgnoreCase(source.sha256() == null ? "" : source.sha256())) {
            return CommandAccepted.of(def.name(), "unchanged", Map.of("provider", "/api/v1/providers/" + def.name()));
        }
        SpecImport imported = OpenApiImporter.parse(latest.raw());
        ObjectNode tree = tree(current.yaml());
        ObjectNode spec = child(tree, "spec");
        JsonNode actionNodes = spec.path("actions");
        List<Problem> gone = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        List<String> kept = new ArrayList<>();
        int compared = 0;
        List<Action> actions = def.actions();
        for (int i = 0; i < actions.size(); i++) {
            Action a = actions.get(i);
            if (blank(a.operation()) || (a.method() != null && a.path() != null)) {
                continue;
            }
            compared++;
            Optional<SpecImport.Operation> op = imported.operation(a.operation());
            if (op.isEmpty()) {
                gone.add(Problem.error("refresh.operationGone", a.id(), "spec.actions[" + i + "].operation", "The new spec has no operation " + a.operation()
                        + ", which " + a.id() + " calls; publish the provider without that action first, or refresh to a ref that still has it"));
                continue;
            }
            ObjectNode node = (ObjectNode) actionNodes.get(i);
            boolean updated = false;
            if (op.get().input() != null && !op.get().input().equals(a.input())) {
                node.set("input", op.get().input());
                updated = true;
            }
            JsonNode output = op.get().output() != null ? op.get().output() : a.output();
            if (op.get().output() != null && !op.get().output().equals(a.output())) {
                node.set("output", output);
                updated = true;
            }
            if (updated) {
                changed.add(a.id());
            }
            JsonNode override = def.override(a.id());
            if (override != null && override.has("response")) {
                JsonNode real = realResponse(def, a);
                if (real != null && Schemas.mismatches(output, real).isEmpty()) {
                    // a newer spec keeps the override unless it now agrees with the real response
                    dropOverride(spec, a.id());
                    dropped.add(a.id());
                } else {
                    kept.add(a.id());
                }
            }
        }
        if (!gone.isEmpty()) {
            throw new ConflictException("The new spec drops " + gone.size() + (gone.size() == 1 ? " operation" : " operations")
                    + " that provider " + def.name() + "'s actions call", gone);
        }
        String version = Semver.parse(def.metadata().version()).bumpMinor().toString();
        child(tree, "metadata").put("version", version);
        ObjectNode pin = child(spec, "spec");
        pin.put("sha256", sha);
        if (source.fromGit()) {
            pin.put("commit", latest.commit());
            if (!blank(c.ref())) {
                child(pin, "git").put("ref", latest.ref());
            }
        } else if (pin.has("content")) {
            pin.put("content", latest.raw());
        }
        String yaml = ProviderYaml.toYaml(tree);
        String detail = "Read " + where(source) + (latest.commit() == null ? "" : " at " + shortCommit(latest.commit()))
                + (imported.version() == null ? "" : " (spec version " + imported.version() + ")") + ": "
                + changed.size() + " of " + compared + (compared == 1 ? " action's schemas changed" : " actions' schemas changed")
                + (dropped.isEmpty() ? "" : "; override dropped for " + String.join(", ", dropped) + " (the spec now agrees with the real response)")
                + (kept.isEmpty() ? "" : "; override kept for " + String.join(", ", kept));
        List<Check> extra = new ArrayList<>();
        extra.add(new Check(SPEC_REFRESH, "Spec refreshed", "passed", detail));
        // the real responses judged against the new schemas: a refresh that breaks one cannot merge
        extra.addAll(reviewChecks(yaml, ProviderReview.RESPONSES));
        return openChange(who, current, yaml, "Provider " + def.title() + " " + version + ": spec refreshed", SPEC_REFRESH, DEFAULT_STRATEGY, extra);
    }

    /** The newest real answer of an action: a Try it call the platform recorded, else a sample the wizard recorded. */
    private JsonNode realResponse(ProviderDefinition def, Action a) {
        Optional<ActionTry> answer = evidence.evidence(def.name()).lastAnswer(a.id(), ProviderReview.sandbox(def));
        if (answer.isPresent()) {
            return answer.get().response();
        }
        return a.sample() != null && a.recorded() != null ? a.sample() : null;
    }

    private static void dropOverride(ObjectNode spec, String action) {
        JsonNode overrides = spec.get("overrides");
        if (overrides == null || !overrides.isObject()) {
            return;
        }
        JsonNode block = overrides.get(action);
        if (block != null && block.isObject()) {
            ((ObjectNode) block).remove("response");
            if (block.isEmpty()) {
                ((ObjectNode) overrides).remove(action);
            }
        }
        if (overrides.isEmpty()) {
            spec.remove("overrides");
        }
    }

    private static String where(SpecSource source) {
        if (source.fromGit()) {
            return source.git().path() + " in " + source.git().repo();
        }
        return source.pasted() ? "the pasted document" : source.url().trim();
    }

    private static String shortCommit(String commit) {
        return commit.length() > 12 ? commit.substring(0, 12) : commit;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public Mono<ConnectionResult.Results> testConnection(TestConnection command) {
        return CurrentIdentity.current().flatMap(who -> Mono.defer(() -> {
            if (command == null || command.endpoints() == null || command.endpoints().isEmpty()) {
                throw new IllegalArgumentException("endpoints is required: one URL per environment");
            }
            String provider = command.provider() == null || command.provider().isBlank() ? null : command.provider().trim();
            if (provider != null) {
                ContentFiles.requireSlug("The provider name", provider);
            }
            Auth auth = authOf(command.auth());
            // results kept as evidence for a provider's review: only its team may add to them, judged before anything is probed;
            // the answer is the team the entries are recorded for
            Mono<Optional<String>> reviewer = provider == null ? Mono.just(Optional.empty())
                    : blocking(() -> Optional.ofNullable(requireReviewer(who, provider, command.owner())));
            // probed at once, recorded in the order of the environments; the credential goes to every endpoint named:
            // only once the caller may use it
            return reviewer.flatMapMany(team -> CredentialUse.require(credentials, who, auth.credential()).thenMany(Flux.fromIterable(command.endpoints().entrySet())
                    .flatMapSequential(e -> probe(e.getKey(), e.getValue(), auth).map(r -> Map.entry(e.getValue() == null ? "" : e.getValue().trim(), r)))
                    .concatMap(t -> provider == null ? Mono.just(t.getValue()) : blocking(() -> {
                        ConnectionResult r = t.getValue();
                        evidence.recordConnection(provider, new ConnectionTest(r.environment(), t.getKey().isEmpty() ? null : t.getKey(),
                                auth.credential(), r.ok(), r.durationMs(), Redactor.redact(r.message(), secrets::known, REDACTED), clock.instant(), who.id(),
                                team.orElse(null)));
                        return r;
                    })))).collectList()
                    .flatMap(results -> blocking(() -> {
                        ObjectNode data = F.objectNode();
                        if (provider != null) {
                            data.put("provider", provider);
                        }
                        ArrayNode environments = data.putArray("environments");
                        ObjectNode ok = data.putObject("ok");
                        for (ConnectionResult r : results) {
                            environments.add(r.environment());
                            ok.put(r.environment(), r.ok());
                        }
                        if (auth.credential() != null && !auth.credential().isBlank()) {
                            data.put("credential", auth.credential());
                        }
                        data.put("by", who.id());
                        events.publish(CONNECTION_TESTED_EVENT, "provider", provider, who.id(), data);
                        return new ConnectionResult.Results(results);
                    }));
        }));
    }

    private Mono<ConnectionResult> probe(String environment, String url, Auth auth) {
        if (url == null || url.isBlank()) {
            return Mono.just(new ConnectionResult(environment, false, 0, "No endpoint for " + environment));
        }
        return http.probe(url.trim(), auth)
                .map(r -> new ConnectionResult(environment, r.ok(), r.durationMs(), r.message()))
                .onErrorResume(e -> Mono.just(new ConnectionResult(environment, false, 0, reason(e))));
    }

    @Override
    public Mono<TryResult> tryAction(TryAction command) {
        return CurrentIdentity.current().flatMap(who -> Mono.defer(() -> {
            if (command == null || command.yaml() == null || command.yaml().isBlank()) {
                throw new IllegalArgumentException("yaml is required");
            }
            if (command.action() == null || command.action().isBlank()) {
                throw new IllegalArgumentException("action is required");
            }
            ProviderDefinition def = ProviderYaml.parse(command.yaml());
            List<String> ids = def.actions().stream().map(Action::id).toList();
            Action action = def.action(command.action()).orElseThrow(() -> new IllegalArgumentException(
                    "Provider " + def.name() + " has no action '" + command.action() + "'" + Suggest.closest(command.action(), ids).map(s -> "; did you mean " + s + "?").orElse("")));
            String environment = environmentOf(def, command.environment());
            JsonNode input = command.input() == null || command.input().isNull() ? F.objectNode() : command.input();
            String endpoint = endpointOf(def, environment);
            String credential = blank(def.spec().auth() == null ? null : def.spec().auth().credential()) ? null : def.spec().auth().credential().trim();
            // the try is evidence for the provider's review: only its team may add to it, and the call resolves the
            // draft's credentials: only once the caller may use them
            return blocking(() -> Optional.ofNullable(requireReviewer(who, def.name(), def.owner()))).flatMap(team -> CredentialUse.guarded(credentials, who, () -> {
                long started = System.nanoTime();
                // the status the system answered with, where the adapter speaks HTTP; 200 for one that does not
                AtomicInteger answered = new AtomicInteger(200);
                // a review call: it reaches the system, but records no sample under a published provider's name
                return providerCalls.call(new ProviderCall(null, 1, def, action, environment, input, false)).contextWrite(c -> ReviewCall.with(c, answered))
                        .flatMap(response -> {
                            long ms = elapsedMs(started);
                            List<Mismatch> mismatches = Schemas.mismatches(def.effectiveOutput(action), response);
                            return verify(def, action, environment, input).map(v -> {
                                Instant at = clock.instant();
                                return new TryResult(true, answered.get(), ms, response, mismatches, v.orElse(null), environment, at,
                                        withSample(command.yaml(), action.id(), response, environment, at, secrets::known));
                            });
                        })
                        .onErrorResume(ProviderCallException.class, e -> Mono.just(new TryResult(false, e.status() > 0 ? e.status() : 502,
                                elapsedMs(started), errorBody(e), List.of(), null, environment, clock.instant(), null)))
                        .flatMap(result -> blocking(() -> {
                            // the evidence copy is redacted; the live answer goes back to the caller as it came
                            String errorClass = result.ok() ? null : result.response().path("error").path("class").asText(null);
                            evidence.recordTry(def.name(), new ActionTry(action.id(), environment, result.ok(), result.status(), result.durationMs(),
                                    errorClass, result.ok() ? redacted(result.response(), secrets::known) : null, result.verified(), result.at(), who.id(),
                                    endpoint, credential, team.orElse(null)));
                            ObjectNode data = F.objectNode();
                            data.put("provider", def.name());
                            data.put("action", action.id());
                            data.put("environment", environment);
                            data.put("ok", result.ok());
                            data.put("status", result.status());
                            if (errorClass != null) {
                                data.put("errorClass", errorClass);
                            }
                            data.put("durationMs", result.durationMs());
                            data.put("by", who.id());
                            events.publish(TRIED_EVENT, "provider", def.name(), who.id(), data); // the outcome, never the response
                            return result;
                        }));
            }, credentialsOf(def).toArray(String[]::new)));
        }));
    }

    /** The references a call of the provider resolves: its auth, and the read token of a spec or a playbook source kept in Git. */
    static List<String> credentialsOf(ProviderDefinition def) {
        List<String> refs = new ArrayList<>();
        ProviderDefinition.Spec spec = def.spec();
        if (spec == null) {
            return refs;
        }
        if (spec.auth() != null) {
            refs.add(spec.auth().credential());
        }
        if (spec.specSource() != null && spec.specSource().fromGit()) {
            refs.add(spec.specSource().git().credential());
        }
        if (spec.source() != null) {
            refs.add(spec.source().path("git").path("credential").asText(null));
        }
        return refs.stream().filter(r -> r != null && r.startsWith("vault:")).distinct().toList();
    }

    /**
     * The provider draft with a real response saved as the action's sample (the answer dry runs
     * give), where it came from in {@code recorded}, and secrets kept out of the file
     * ({@link #redacted}). Null when the draft is not a provider mapping with that action.
     */
    static String withSample(String yaml, String actionId, JsonNode response, String environment, Instant at, Predicate<String> knownSecret) {
        JsonNode root;
        try {
            root = WorkflowYaml.readTree(yaml);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (root == null || !root.isObject() || response == null) {
            return null;
        }
        for (JsonNode a : root.path("spec").path("actions")) {
            if (a.isObject() && actionId.equals(a.path("id").asText(null))) {
                ObjectNode action = (ObjectNode) a;
                action.set("sample", redacted(response, knownSecret));
                ObjectNode recorded = action.putObject("recorded");
                recorded.put("environment", environment);
                recorded.put("at", at.toString());
                return ProviderYaml.toYaml(root);
            }
        }
        return null;
    }

    /**
     * A copy with secret-looking fields (token, password, api key...), the secrets the platform
     * resolved wherever they appear, and token-shaped words replaced by {@value #REDACTED}; a
     * {@code vault:} reference stays.
     */
    static JsonNode redacted(JsonNode node, Predicate<String> knownSecret) {
        return Redactor.redact(node, knownSecret, REDACTED);
    }

    /** After a destructive try, the verify action proves the effect: {@code expect: notFound} means the thing is gone. */
    private Mono<Optional<Boolean>> verify(ProviderDefinition def, Action action, String environment, JsonNode input) {
        if (!action.destructive() || action.verify() == null || action.verify().action() == null) {
            return Mono.just(Optional.empty());
        }
        Optional<Action> verifyAction = def.action(action.verify().action());
        if (verifyAction.isEmpty()) {
            return Mono.just(Optional.of(false));
        }
        String expect = action.verify().expect() == null ? "notFound" : action.verify().expect();
        return providerCalls.call(new ProviderCall(null, 2, def, verifyAction.get(), environment, input, false)).contextWrite(ReviewCall::with)
                .map(response -> Optional.of(judge(expect, response)))
                .onErrorResume(ProviderCallException.class, e -> Mono.just(Optional.of("notFound".equals(expect) && "not_found".equals(e.errorClass()))));
    }

    static boolean judge(String expect, JsonNode response) {
        return switch (expect) {
            case "notFound" -> false;
            case "ok", "exists" -> true;
            default -> response != null && (expect.equals(response.path("state").asText(null)) || expect.equals(response.path("status").asText(null)));
        };
    }

    @Override
    public Mono<ValidationResult> validate(Validate command) {
        return blocking(() -> {
            if (command == null || command.yaml() == null || command.yaml().isBlank()) {
                throw new IllegalArgumentException("yaml is required");
            }
            ProviderChecks.Result r = ProviderChecks.check(command.yaml());
            return new ValidationResult(r.checks(), r.problems(), reviewOf(command.yaml()));
        });
    }

    /** The review of a provider file against what was proved for its name; null when the file cannot be read as a provider. */
    ProviderReview.Result reviewOf(String yaml) {
        ProviderDefinition def;
        try {
            def = ProviderYaml.parse(yaml);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return ProviderReview.review(def, evidence.evidence(def.name()));
    }

    /** A file posted for publishing that passed its own checks. */
    private record Publishable(ProviderDefinition def, String strategy, ProviderChecks.Result checks) {
    }

    @Override
    public Mono<CommandAccepted> publish(Publish command) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> publishable(command))
                .flatMap(p -> candidate(p.def().name()).flatMap(candidate -> blocking(() -> publish(who, command, p, candidate)))));
    }

    private static Publishable publishable(Publish command) {
        if (command == null || command.yaml() == null || command.yaml().isBlank()) {
            throw new IllegalArgumentException("yaml is required");
        }
        String strategy = command.strategy() == null || command.strategy().isBlank() ? DEFAULT_STRATEGY : command.strategy().trim();
        if (!STRATEGIES.contains(strategy)) {
            throw new IllegalArgumentException("strategy is canary or all");
        }
        ProviderChecks.Publishable file = ProviderChecks.publishable(command.yaml(), checks -> {
            int n = checks.errors().size();
            return new ConflictException("The provider has " + n + (n == 1 ? " problem" : " problems") + " to fix before it can be published", checks.problems());
        });
        return new Publishable(file.definition(), strategy, file.checks());
    }

    private CommandAccepted publish(Identity who, Publish command, Publishable p, Optional<String> candidate) {
        ProviderDefinition def = p.def();
        String strategy = p.strategy();
        ProviderChecks.Result checks = p.checks();
        Optional<StoredProvider> stored = store.get(def.name());
        // the published provider's owner decides, not the owner the posted file names: handing a
        // provider to another team is itself an edit its owner makes, and nobody claims one by re-posting it
        if (stored.isPresent()) {
            AccessRules.requireEdit(who, stored.get().definition().owner(), "provider " + def.name());
        }
        AccessRules.requireEdit(who, def.owner(), "provider " + def.name());
        String version = def.metadata().version();
        if (stored.filter(c -> version.equals(c.definition().metadata().version())).isPresent() || store.version(def.name(), version).isPresent()) {
            throw new ConflictException("Version " + version + " of provider " + def.name() + " is already published; bump metadata.version");
        }
        Optional<StoredProvider> current = stored.map(c -> latest(def.name(), candidate));
        boolean open = changeRequests.all().stream().anyMatch(cr -> cr.kind() == ChangeRequest.Kind.PROVIDER && cr.status() == ChangeRequest.Status.OPEN
                && def.name().equals(cr.name()) && version.equals(cr.version()));
        if (open) {
            throw new ConflictException("A change request for provider " + def.name() + " " + version + " is already open");
        }
        requireNoOpenChange(def.name());
        // the review step: what the wizard proved, judged by the platform (credentials work, every action tried...)
        ProviderReview.Result review = ProviderReview.review(def, evidence.evidence(def.name()));
        if (!review.ready()) {
            int n = review.errors().size();
            throw new ConflictException("The provider's review has " + n + (n == 1 ? " problem" : " problems") + " to fix before it can be published",
                    review.errors());
        }
        List<Check> all = new ArrayList<>(checks.checks());
        all.addAll(review.checks());
        String path = pathOf(def);
        String diff = TextDiff.unified(current.map(StoredProvider::yaml).orElse(null), command.yaml(), path);
        String id = Ids.next("cr");
        ChangeRequest cr = new ChangeRequest(id, ChangeRequest.Kind.PROVIDER, def.name(), version, "change/provider-" + def.name() + "-" + version,
                "Provider " + def.title() + " " + version, who.id(), ChangeRequest.Status.OPEN, List.copyOf(all), strategy, command.yaml(), diff,
                clock.instant(), null, null, null);
        changeRequests.save(cr);
        ObjectNode data = F.objectNode();
        data.put("name", def.name());
        data.put("version", version);
        data.put("changeRequestId", id);
        data.put("by", who.id());
        data.put("owner", def.metadata().owner()); // the team a reviewer is found in
        events.publish(CHANGE_REQUESTED_EVENT, "provider", def.name(), who.id(), data);
        return CommandAccepted.of(id, "open", Map.of("changeRequest", "/api/v1/change-requests/" + id));
    }

    @Override
    public Mono<ProviderDraft> draft(Draft command) {
        return Mono.defer(() -> {
            if (command == null || command.description() == null || command.description().isBlank()) {
                throw new IllegalArgumentException("description is required: say which system and which operations the provider should offer");
            }
            return assistant.draftProvider(command.description().trim());
        });
    }

    // ---------------------------------------------------------------- admin edits (through change requests)

    /**
     * "Use the real response": the schema inferred from the response is merged over the action's
     * output in {@code overrides}, as a change request for the next patch version.
     */
    @Override
    public Mono<CommandAccepted> overrides(String name, SetOverride command) {
        return CurrentIdentity.current().flatMap(who -> candidate(name).flatMap(candidate -> blocking(() -> {
            StoredProvider current = latest(name, candidate);
            ProviderDefinition def = current.definition();
            AccessRules.requireEdit(who, def.owner(), "provider " + name);
            if (command == null || command.action() == null || command.action().isBlank()) {
                throw new IllegalArgumentException("action is required");
            }
            Action action = def.action(command.action()).orElseThrow(() -> new IllegalArgumentException("Provider " + name + " has no action '" + command.action() + "'"));
            if (command.response() == null || command.response().isNull() || command.response().isMissingNode()) {
                throw new IllegalArgumentException("response is required: the real response the schema is inferred from");
            }
            requireNoOpenChange(name);
            ObjectNode response = Schemas.merge(def.effectiveOutput(action), Schemas.infer(command.response()));
            ObjectNode tree = tree(current.yaml());
            child(child(child(tree, "spec"), "overrides"), action.id()).set("response", response);
            if (tree.equals(tree(current.yaml()))) {
                return CommandAccepted.of(name, "unchanged", Map.of("provider", "/api/v1/providers/" + name));
            }
            String version = nextPatch(tree, def);
            String yaml = ProviderYaml.toYaml(tree);
            return openChange(who, current, yaml, "Provider " + def.title() + " " + version + ": " + action.id() + " uses the real response", "overrides",
                    editStrategy(candidate), reviewChecks(yaml, ProviderReview.RESPONSES));
        })));
    }

    @Override
    public Mono<CommandAccepted> slo(String name, SetSlo command) {
        return CurrentIdentity.current().flatMap(who -> candidate(name).flatMap(candidate -> blocking(() -> {
            StoredProvider current = latest(name, candidate);
            ProviderDefinition def = current.definition();
            AccessRules.requireEdit(who, def.owner(), "provider " + name);
            if (command == null || (command.successRate() == null && command.p95Ms() == null && command.channel() == null)) {
                throw new IllegalArgumentException("Give successRate, p95Ms or channel");
            }
            if (command.successRate() != null && (command.successRate() < 0 || command.successRate() > 100)) {
                throw new IllegalArgumentException("successRate is a percentage between 0 and 100");
            }
            if (command.p95Ms() != null && command.p95Ms() <= 0) {
                throw new IllegalArgumentException("p95Ms is a positive number of milliseconds");
            }
            requireNoOpenChange(name);
            ObjectNode tree = tree(current.yaml());
            ObjectNode spec = child(tree, "spec");
            ObjectNode slo = child(spec, "slo");
            if (!slo.has("successRate")) {
                slo.put("successRate", def.slo().successRate());
            }
            if (!slo.has("p95Ms")) {
                slo.put("p95Ms", def.slo().p95Ms());
            }
            if (command.successRate() != null) {
                slo.put("successRate", command.successRate());
            }
            if (command.p95Ms() != null) {
                slo.put("p95Ms", command.p95Ms());
            }
            if (command.channel() != null) {
                if (command.channel().isBlank()) {
                    spec.remove("alerts");
                } else {
                    child(spec, "alerts").put("channel", command.channel().trim());
                }
            }
            if (sameSlo(tree, tree(current.yaml()))) {
                return CommandAccepted.of(name, "unchanged", Map.of("provider", "/api/v1/providers/" + name));
            }
            String version = nextPatch(tree, def);
            String yaml = ProviderYaml.toYaml(tree);
            return openChange(who, current, yaml, "Provider " + def.title() + " " + version + ": SLO and alerts", "slo",
                    editStrategy(candidate), reviewChecks(yaml, ProviderReview.SLO_SUGGESTED));
        })));
    }

    /** The same SLO and alert channel once the defaults the edit wrote in are counted as what the file meant. */
    private static boolean sameSlo(ObjectNode edited, ObjectNode original) {
        JsonNode a = edited.path("spec");
        JsonNode b = original.path("spec");
        ProviderDefinition.Slo before = ProviderYaml.fromJson(original).slo();
        ProviderDefinition.Slo after = ProviderYaml.fromJson(edited).slo();
        return before.equals(after) && a.path("alerts").equals(b.path("alerts"));
    }

    /** The provider's next patch version, written into the edited file. */
    private static String nextPatch(ObjectNode tree, ProviderDefinition def) {
        String version = Semver.parse(def.metadata().version()).bumpPatch().toString();
        child(tree, "metadata").put("version", version);
        return version;
    }

    /** The review checks named, judged on the edited file with what the platform saw of the provider. */
    private List<Check> reviewChecks(String yaml, String... ids) {
        List<String> wanted = List.of(ids);
        ProviderDefinition def = ProviderYaml.parse(yaml);
        return ProviderReview.review(def, evidence.evidence(def.name())).checks().stream()
                .filter(c -> wanted.contains(c.id()))
                .toList();
    }

    /**
     * The version an edit builds on: the candidate of a provider release still rolling out (so the
     * edit keeps what the canary carries), else the current version.
     */
    private StoredProvider latest(String name, Optional<String> candidate) {
        StoredProvider current = store.get(name).orElseThrow(() -> new NotFoundException("Provider", name));
        return candidate.flatMap(v -> store.version(name, v)).orElse(current);
    }

    /**
     * The candidate version of the provider's running release, if one is running: composed into the
     * command rather than waited for, and asked off the request thread since a release store may block.
     */
    private Mono<Optional<String>> candidate(String name) {
        return releases.providerCandidate(name).subscribeOn(Schedulers.boundedElastic()).map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /** Overrides and SLO edits change no call: they reach every call at once, unless they build on a candidate still rolling out. */
    private static String editStrategy(Optional<String> candidate) {
        return candidate.isPresent() ? DEFAULT_STRATEGY : ALL;
    }

    /** One change at a time per provider: two open change requests would publish over each other. */
    private void requireNoOpenChange(String name) {
        changeRequests.all().stream()
                .filter(cr -> cr.kind() == ChangeRequest.Kind.PROVIDER && cr.status() == ChangeRequest.Status.OPEN && name.equals(cr.name()))
                .findFirst()
                .ifPresent(cr -> {
                    throw new ConflictException("Change request " + cr.id() + " for provider " + name + " " + cr.version() + " is still open; merge or close it first");
                });
    }

    /**
     * Opens a change request that publishes {@code yaml} as the provider's next version, rolled out
     * by {@code strategy}, with the file checks and {@code extra} checks; refused while the file
     * has errors.
     */
    private CommandAccepted openChange(Identity who, StoredProvider current, String yaml, String title, String change, String strategy,
            List<Check> extra) {
        ProviderChecks.Result checks = ProviderChecks.check(yaml);
        if (checks.hasErrors()) {
            int n = checks.errors().size();
            throw new ConflictException("The changed provider has " + n + (n == 1 ? " problem" : " problems") + " to fix", checks.problems());
        }
        ProviderDefinition def = ProviderYaml.parse(yaml);
        String version = def.metadata().version();
        List<Check> all = new ArrayList<>(checks.checks());
        all.addAll(extra);
        String path = current.path() == null ? pathOf(def) : current.path();
        String diff = TextDiff.unified(current.yaml(), yaml, path);
        String id = Ids.next("cr");
        ChangeRequest cr = new ChangeRequest(id, ChangeRequest.Kind.PROVIDER, def.name(), version, "change/provider-" + def.name() + "-" + version,
                title, who.id(), ChangeRequest.Status.OPEN, List.copyOf(all), strategy, yaml, diff, clock.instant(), null, null, null);
        changeRequests.save(cr);
        ObjectNode data = F.objectNode();
        data.put("name", def.name());
        data.put("version", version);
        data.put("changeRequestId", id);
        data.put("by", who.id());
        data.put("owner", def.metadata().owner());
        data.put("change", change);
        data.put("strategy", strategy);
        events.publish(CHANGE_REQUESTED_EVENT, "provider", def.name(), who.id(), data);
        return CommandAccepted.of(id, "open", Map.of("changeRequest", "/api/v1/change-requests/" + id, "provider", "/api/v1/providers/" + def.name()));
    }

    // ---------------------------------------------------------------- merged change requests

    /**
     * Publishes the provider file of a merged change request: as the current version, or, rolled
     * out by a canary, beside it until the release is promoted. The workflows slice calls it when it
     * merges a provider change request, after saving the change request as merged, and fails the
     * merge when it errors. Idempotent: a version the store already keeps was published before, and
     * a second call for the same change request changes nothing and publishes no event.
     *
     * @throws NotFoundException (404) when there is no such change request
     * @throws IllegalArgumentException (400) when it is not a provider change request
     * @throws ConflictException (409) when it is not merged, or carries no provider file
     */
    @Override
    public Mono<Void> publishMerged(String changeRequestId) {
        return blocking(() -> {
            if (changeRequestId == null || changeRequestId.isBlank()) {
                throw new IllegalArgumentException("changeRequestId is required");
            }
            ChangeRequest cr = changeRequests.get(changeRequestId).orElseThrow(() -> new NotFoundException("change request", changeRequestId));
            if (cr.kind() != ChangeRequest.Kind.PROVIDER) {
                throw new IllegalArgumentException("change request " + changeRequestId + " is not a provider change request");
            }
            if (cr.status() != ChangeRequest.Status.MERGED) {
                throw new ConflictException("change request " + changeRequestId + " is " + cr.status().json() + "; only a merged one is published");
            }
            if (cr.yaml() == null || cr.yaml().isBlank()) {
                throw new ConflictException("change request " + changeRequestId + " carries no provider file to publish");
            }
            String by = !blank(cr.mergedBy()) ? cr.mergedBy() : !blank(cr.author()) ? cr.author() : Identity.SYSTEM.id();
            return publishMerged(cr.id(), cr.yaml(), by, cr.strategy());
        }).then();
    }

    /**
     * Kept for the change requests the workflows slice merges without calling {@link #publishMerged(String)}:
     * a merged provider change request publishes the provider (a no-op when it was published already);
     * a promoted provider release makes its candidate current.
     */
    void on(DomainEvent event) {
        if (RELEASE_PROMOTED.equals(event.type()) && event.data() != null && event.data().hasNonNull("provider")) {
            promoted(event.data());
            return;
        }
        if (!MERGED_EVENT.equals(event.type()) || event.data() == null) {
            return;
        }
        JsonNode d = event.data();
        if (!"provider".equalsIgnoreCase(d.path("kind").asText(""))) {
            return;
        }
        String id = d.path("id").asText(event.aggregateId());
        String yaml = d.path("yaml").asText(null);
        if (yaml == null || yaml.isBlank()) {
            yaml = changeRequests.get(id).map(ChangeRequest::yaml).orElse(null);
        }
        if (yaml == null || yaml.isBlank()) {
            log.warn("change request {} merged without a provider file; nothing published", id);
            return;
        }
        String by = d.path("by").asText(null);
        if (by == null || by.isBlank()) {
            by = event.actor() == null ? Identity.SYSTEM.id() : event.actor();
        }
        publishMerged(id, yaml, by, changeRequests.get(id).map(ChangeRequest::strategy).orElse(null));
    }

    /**
     * Publishes {@code yaml}, the file of merged change request {@code id}; returns false, and
     * changes nothing, when the store already keeps that version (it was published before).
     */
    boolean publishMerged(String id, String yaml, String by, String changeRequestStrategy) {
        ProviderDefinition def = ProviderYaml.parse(yaml);
        String version = def.metadata().version();
        if (store.version(def.name(), version).isPresent()) {
            log.info("provider {} {} of change request {} is published already; nothing to do", def.name(), version, id);
            return false;
        }
        String previous = store.get(def.name()).map(p -> p.definition().metadata().version()).orElse(null);
        String strategy = blank(changeRequestStrategy) ? DEFAULT_STRATEGY : changeRequestStrategy;
        StoredProvider merged = new StoredProvider(def, yaml, pathOf(def), clock.instant(), by);
        boolean canary = previous != null && !previous.equals(version) && !ALL.equals(strategy);
        if (canary) {
            store.saveVersion(merged); // the release routes calls to it; the current version keeps the rest
        } else {
            store.save(merged);
        }
        ObjectNode data = F.objectNode();
        data.put("name", def.name());
        data.put("version", version);
        data.put("by", by);
        data.put("previous", previous);
        data.put("strategy", strategy);
        events.publish(PUBLISHED_EVENT, "provider", def.name(), by, data);
        log.info("provider {} {} published by {} from change request {} ({})", def.name(), version, by, id,
                canary ? "canary from " + previous : "current");
        return true;
    }

    /**
     * A provider release was promoted: its candidate becomes the current version, unless another
     * version was made current since the release started.
     */
    private void promoted(JsonNode d) {
        String name = d.path("provider").asText(null);
        String from = d.path("from").asText(null);
        String to = d.path("to").asText(null);
        if (name == null || to == null) {
            return;
        }
        String current = store.get(name).map(p -> p.definition().metadata().version()).orElse(null);
        if (to.equals(current)) {
            return;
        }
        if (from != null && !from.equals(current)) {
            log.info("provider {} {} promoted, but {} is current now, not {}; nothing changed", name, to, current, from);
            return;
        }
        store.version(name, to).ifPresentOrElse(candidate -> {
            store.save(candidate);
            log.info("provider {} {} promoted to current (was {})", name, to, current);
        }, () -> log.warn("provider {} {} promoted, but that version is not kept", name, to));
    }

    // ---------------------------------------------------------------- helpers

    static String pathOf(ProviderDefinition def) {
        String team = def.owner() == null || def.owner().isBlank() ? "shared" : def.owner();
        return ContentFiles.FOLDERS.get(1) + "/" + team + "/" + def.name() + ".yaml";
    }

    static Auth authOf(JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Auth("none", null, null);
        }
        return new Auth(node.path("type").asText("none"), text(node, "credential"), text(node, "header"));
    }

    static String environmentOf(ProviderDefinition def, String environment) {
        List<String> environments = def.environments();
        if (environments.isEmpty()) {
            return environment == null || environment.isBlank() ? "dev" : environment;
        }
        if (environment == null || environment.isBlank()) {
            if (environments.size() == 1) {
                return environments.get(0);
            }
            throw new IllegalArgumentException("environment is required: one of " + environments);
        }
        if (!environments.contains(environment)) {
            throw new IllegalArgumentException("Provider " + def.name() + " has no environment '" + environment + "'; one of " + environments);
        }
        return environment;
    }

    /** The URL a call to {@code environment} goes to, trimmed as Test connection records it; null when the provider names none. */
    static String endpointOf(ProviderDefinition def, String environment) {
        Map<String, String> endpoints = def.spec() == null || def.spec().endpoints() == null ? Map.of() : def.spec().endpoints();
        String url = environment == null ? null : endpoints.get(environment);
        return url == null || url.isBlank() ? null : url.trim();
    }

    /**
     * Throws unless {@code who} may add review evidence (a connection test or a try) for the provider
     * {@code name}, and answers the team the entry is recorded for. A published provider: its owner
     * team (an admin always). One not published yet is held by the team that recorded its first
     * evidence ({@link ReviewEvidence#claim()}): only that team adds to it (an admin always), so no
     * other team pushes its entries out of the window; before that, the draft's owner team when it
     * names one, else any member of a team, whose first team then holds it. A store read: call where
     * blocking is allowed.
     */
    private String requireReviewer(Identity who, String name, String draftOwner) {
        Optional<StoredProvider> stored = store.get(name);
        if (stored.isPresent()) {
            String owner = stored.get().definition().owner();
            AccessRules.requireEdit(who, owner, "provider " + name);
            return owner;
        }
        if (!blank(draftOwner)) {
            AccessRules.requireEdit(who, draftOwner, "provider " + name);
        } else if (!who.admin() && who.teams().isEmpty()) {
            throw new ForbiddenException(who.name() + " may not test or try provider " + name + ": only members of a team may");
        }
        Optional<String> claim = evidence.evidence(name).claim();
        if (claim.isPresent()) {
            boolean sameDraft = blank(draftOwner) || claim.get().equals(draftOwner.trim());
            if (!who.admin() && (!sameDraft || !who.teams().contains(claim.get()))) {
                throw new ForbiddenException("Provider " + name + " is being drafted by " + claim.get()
                        + ": only that team adds to its review evidence until it is published");
            }
            return claim.get();
        }
        return !blank(draftOwner) ? draftOwner.trim() : who.teams().isEmpty() ? null : who.teams().get(0);
    }

    /** Work that may block (stores, change requests, the GitHub API) runs on the bounded elastic scheduler. */
    static <T> Mono<T> blocking(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    static ObjectNode errorBody(ProviderCallException e) {
        ObjectNode error = F.objectNode();
        error.put("class", e.errorClass());
        error.put("message", e.getMessage());
        error.put("retryable", e.retryable());
        if (e.status() > 0) {
            error.put("status", e.status());
        }
        ObjectNode body = F.objectNode();
        body.set("error", error);
        return body;
    }

    static ObjectNode tree(String yaml) {
        JsonNode t = WorkflowYaml.readTree(yaml);
        if (!t.isObject()) {
            throw new IllegalStateException("The stored provider file is not a mapping");
        }
        return (ObjectNode) t;
    }

    static ObjectNode child(ObjectNode parent, String key) {
        JsonNode existing = parent.get(key);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        return parent.putObject(key);
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String reason(Throwable e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
