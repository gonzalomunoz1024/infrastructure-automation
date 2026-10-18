package com.iaf.platform.providers.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Suggest;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.events.EventBus;
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
import com.iaf.platform.providers.domain.ProviderReview;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;
import com.iaf.platform.providers.domain.SpecImport;
import com.iaf.platform.providers.domain.TextDiff;
import com.iaf.platform.providers.domain.TryResult;
import com.iaf.platform.providers.ports.inbound.ProviderCommands;
import com.iaf.platform.providers.ports.outbound.HttpProbePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort.StoredProvider;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.ChangeRequest;
import com.iaf.platform.workflows.domain.ChangeRequest.Check;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.outbound.ChangeRequestPort;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The Add provider wizard's backend and the admin edits. Publishing opens a change request; the
 * provider is saved when the workflows slice merges it and publishes {@code change-request.merged},
 * which this handler listens to.
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class ProviderCommandHandler implements ProviderCommands {

    public static final String MERGED_EVENT = "change-request.merged";
    public static final String PUBLISHED_EVENT = "provider.published";
    public static final String UPDATED_EVENT = "provider.updated";
    public static final String CHANGE_REQUESTED_EVENT = "provider.change-requested";

    /** What a secret-looking field of a recorded sample becomes in the provider file. */
    public static final String REDACTED = "vault:redacted";

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

    @PostConstruct
    void listen() {
        events.register(this::on);
    }

    // ---------------------------------------------------------------- wizard

    @Override
    public Mono<SpecImport> importSpec(ImportSpec command) {
        return Mono.defer(() -> {
            if (command == null) {
                throw new IllegalArgumentException("type is required");
            }
            String type = command.type() == null || command.type().isBlank() ? "openapi" : command.type().trim();
            if (!"openapi".equalsIgnoreCase(type)) {
                throw new IllegalArgumentException("Only OpenAPI specs can be imported; type was " + type);
            }
            boolean hasUrl = command.url() != null && !command.url().isBlank();
            boolean hasContent = command.content() != null && !command.content().isBlank();
            if (!hasUrl && !hasContent) {
                throw new IllegalArgumentException("Give the spec's url or paste its content");
            }
            if (hasContent) {
                return Mono.fromCallable(() -> OpenApiImporter.parse(command.content()));
            }
            String url = command.url().trim();
            return http.fetch(url)
                    .onErrorMap(e -> !(e instanceof IllegalArgumentException), e -> new IllegalArgumentException("Could not fetch " + url + ": " + reason(e)))
                    .map(OpenApiImporter::parse);
        });
    }

    @Override
    public Mono<ConnectionResult.Results> testConnection(TestConnection command) {
        return CurrentIdentity.current().flatMap(who -> Flux.defer(() -> {
            if (command == null || command.endpoints() == null || command.endpoints().isEmpty()) {
                throw new IllegalArgumentException("endpoints is required: one URL per environment");
            }
            String provider = command.provider() == null || command.provider().isBlank() ? null : command.provider().trim();
            if (provider != null) {
                ContentFiles.requireSlug("The provider name", provider);
            }
            Auth auth = authOf(command.auth());
            return Flux.fromIterable(command.endpoints().entrySet()).flatMapSequential(e -> probe(e.getKey(), e.getValue(), auth)
                    .flatMap(r -> provider == null ? Mono.just(r) : blocking(() -> {
                        evidence.recordConnection(provider, new ConnectionTest(r.environment(), e.getValue() == null ? null : e.getValue().trim(),
                                auth.credential(), r.ok(), r.durationMs(), r.message(), clock.instant(), who.id()));
                        return r;
                    })));
        }).collectList().map(ConnectionResult.Results::new));
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
            long started = System.nanoTime();
            return providerCalls.call(new ProviderCall(null, 1, def, action, environment, input, false))
                    .flatMap(response -> {
                        long ms = elapsedMs(started);
                        List<Mismatch> mismatches = Schemas.mismatches(def.effectiveOutput(action), response);
                        return verify(def, action, environment, input).map(v -> {
                            Instant at = clock.instant();
                            return new TryResult(true, 200, ms, response, mismatches, v.orElse(null), environment, at,
                                    withSample(command.yaml(), action.id(), response, environment, at));
                        });
                    })
                    .onErrorResume(ProviderCallException.class, e -> Mono.just(new TryResult(false, e.status() > 0 ? e.status() : 502,
                            elapsedMs(started), errorBody(e), List.of(), null, environment, clock.instant(), null)))
                    .flatMap(result -> blocking(() -> {
                        evidence.recordTry(def.name(), new ActionTry(action.id(), environment, result.ok(), result.status(), result.durationMs(),
                                result.ok() ? null : result.response().path("error").path("class").asText(null),
                                result.ok() ? result.response() : null, result.verified(), result.at(), who.id()));
                        return result;
                    }));
        }));
    }

    /**
     * The provider draft with a real response saved as the action's sample (the answer dry runs
     * give), where it came from in {@code recorded}, and secret-looking fields kept out of the file.
     * Null when the draft is not a provider mapping with that action.
     */
    static String withSample(String yaml, String actionId, JsonNode response, String environment, Instant at) {
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
                action.set("sample", redacted(response));
                ObjectNode recorded = action.putObject("recorded");
                recorded.put("environment", environment);
                recorded.put("at", at.toString());
                return ProviderYaml.toYaml(root);
            }
        }
        return null;
    }

    /** A copy with the values of secret-looking fields (token, password, api key...) replaced by {@value #REDACTED}. */
    static JsonNode redacted(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode out = F.objectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), ProviderChecks.secretKey(e.getKey()) && e.getValue().isValueNode() && !e.getValue().isNull()
                    ? F.textNode(REDACTED) : redacted(e.getValue())));
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = F.arrayNode();
            node.forEach(v -> out.add(redacted(v)));
            return out;
        }
        return node.deepCopy();
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
        return providerCalls.call(new ProviderCall(null, 2, def, verifyAction.get(), environment, input, false))
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

    @Override
    public Mono<CommandAccepted> publish(Publish command) {
        return CurrentIdentity.current().flatMap(who -> blocking(() -> {
            if (command == null || command.yaml() == null || command.yaml().isBlank()) {
                throw new IllegalArgumentException("yaml is required");
            }
            String strategy = command.strategy() == null || command.strategy().isBlank() ? "all" : command.strategy().trim();
            if (!STRATEGIES.contains(strategy)) {
                throw new IllegalArgumentException("strategy is canary or all");
            }
            ProviderChecks.Result checks = ProviderChecks.check(command.yaml());
            if (checks.hasErrors()) {
                int n = checks.errors().size();
                throw new ConflictException("The provider has " + n + (n == 1 ? " problem" : " problems") + " to fix before it can be published", checks.problems());
            }
            ProviderDefinition def = ProviderYaml.parse(command.yaml());
            ContentFiles.requireSlug("The provider name", def.name());
            ContentFiles.requireSlug("The owner team", def.owner());
            AccessRules.requireEdit(who, def.owner(), "provider " + def.name());
            String version = def.metadata().version();
            Optional<StoredProvider> current = store.get(def.name());
            if (current.isPresent() && version.equals(current.get().definition().metadata().version())) {
                throw new ConflictException("Version " + version + " of provider " + def.name() + " is already published; bump metadata.version");
            }
            boolean open = changeRequests.all().stream().anyMatch(cr -> cr.kind() == ChangeRequest.Kind.PROVIDER && cr.status() == ChangeRequest.Status.OPEN
                    && def.name().equals(cr.name()) && version.equals(cr.version()));
            if (open) {
                throw new ConflictException("A change request for provider " + def.name() + " " + version + " is already open");
            }
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
            events.publish(CHANGE_REQUESTED_EVENT, "provider", def.name(), who.id(), data);
            return CommandAccepted.of(id, "open", Map.of("changeRequest", "/api/v1/change-requests/" + id));
        }));
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

    // ---------------------------------------------------------------- admin edits

    @Override
    public Mono<CommandAccepted> overrides(String name, SetOverride command) {
        return CurrentIdentity.current().map(who -> {
            StoredProvider current = store.get(name).orElseThrow(() -> new NotFoundException("Provider", name));
            ProviderDefinition def = current.definition();
            AccessRules.requireEdit(who, def.owner(), "provider " + name);
            if (command == null || command.action() == null || command.action().isBlank()) {
                throw new IllegalArgumentException("action is required");
            }
            Action action = def.action(command.action()).orElseThrow(() -> new IllegalArgumentException("Provider " + name + " has no action '" + command.action() + "'"));
            if (command.response() == null || command.response().isNull() || command.response().isMissingNode()) {
                throw new IllegalArgumentException("response is required: the real response the schema is inferred from");
            }
            JsonNode base = action.output();
            JsonNode existing = def.override(action.id());
            if (existing != null && existing.has("response")) {
                base = Schemas.merge(base, existing.get("response"));
            }
            ObjectNode response = Schemas.merge(base, Schemas.infer(command.response()));
            ObjectNode tree = tree(current.yaml());
            child(child(child(tree, "spec"), "overrides"), action.id()).set("response", response);
            saveEdited(current, tree, who, "overrides");
            return CommandAccepted.of(name, "updated", Map.of("provider", "/api/v1/providers/" + name));
        });
    }

    @Override
    public Mono<CommandAccepted> slo(String name, SetSlo command) {
        return CurrentIdentity.current().map(who -> {
            StoredProvider current = store.get(name).orElseThrow(() -> new NotFoundException("Provider", name));
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
            saveEdited(current, tree, who, "slo");
            return CommandAccepted.of(name, "updated", Map.of("provider", "/api/v1/providers/" + name));
        });
    }

    private void saveEdited(StoredProvider current, ObjectNode tree, Identity who, String change) {
        String yaml = ProviderYaml.toYaml(tree);
        ProviderDefinition def = ProviderYaml.parse(yaml);
        store.save(new StoredProvider(def, yaml, current.path() == null ? pathOf(def) : current.path(), clock.instant(), who.id()));
        ObjectNode data = F.objectNode();
        data.put("name", def.name());
        data.put("version", def.metadata().version());
        data.put("by", who.id());
        data.put("change", change);
        events.publish(UPDATED_EVENT, "provider", def.name(), who.id(), data);
    }

    // ---------------------------------------------------------------- merged change requests

    /** The workflows slice merges change requests; a merged provider change request publishes the provider. */
    void on(DomainEvent event) {
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
        ProviderDefinition def = ProviderYaml.parse(yaml);
        String by = d.path("by").asText(null);
        if (by == null || by.isBlank()) {
            by = event.actor() == null ? Identity.SYSTEM.id() : event.actor();
        }
        store.save(new StoredProvider(def, yaml, pathOf(def), clock.instant(), by));
        ObjectNode data = F.objectNode();
        data.put("name", def.name());
        data.put("version", def.metadata().version());
        data.put("by", by);
        events.publish(PUBLISHED_EVENT, "provider", def.name(), by, data);
        log.info("provider {} {} published by {} from change request {}", def.name(), def.metadata().version(), by, id);
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
