package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Decision;
import com.iaf.console.drift.drift.domain.DriftRules;
import com.iaf.console.drift.drift.domain.Finding;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.IgnoredField;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Tier;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.FindingStorePort;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;

import reactor.core.publisher.Mono;

/**
 * The three decisions a person takes on a finding, each one idempotent under its key. A revert
 * starts the type's update workflow with the desired values of the drifted fields (the finding
 * closes as reverted when the job succeeds and the next read agrees); an accept makes the
 * observed values the desired ones through the state service; an ignore silences the fields
 * until the desired state changes, or until a date. Nothing here runs on its own: a revert is
 * always a call.
 */
public final class DriftService {

    private static final Logger log = LoggerFactory.getLogger(DriftService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    /** The job label that ties a revert job to its finding (label names are {@code [a-z0-9-]{1,32}}, P-04). */
    public static final String FINDING_LABEL = "drift-finding";

    private final FindingStorePort findings;
    private final WatchStorePort watches;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final StateServicePort state;
    private final WorkflowStarterPort starter;
    private final OnBehalfOfPort onBehalfOf;
    private final InventorySync sync;
    private final FindingEvents events;
    private final IdempotentCommands idempotent;
    private final DriftMetrics metrics;
    private final Clock clock;

    public DriftService(FindingStorePort findings, WatchStorePort watches, ResourceTypesPort types, PlatformPort platform, StateServicePort state,
            WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, InventorySync sync, FindingEvents events, IdempotentCommands idempotent,
            DriftMetrics metrics, Clock clock) {
        this.findings = findings;
        this.watches = watches;
        this.types = types;
        this.platform = platform;
        this.state = state;
        this.starter = starter;
        this.onBehalfOf = onBehalfOf;
        this.sync = sync;
        this.events = events;
        this.idempotent = idempotent;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** The answer of a revert: the finding and the job that carries it out. */
    public record Reverted(String findingId, String jobId) {
    }

    /**
     * Starts the revert. The job runs through the account's platform service account on behalf of
     * the person: {@code relayedAssertion} is the BFF's {@code X-On-Behalf-Of} for them; without it,
     * the local signer signs for {@code person} where one exists (the demo), and otherwise the
     * revert is refused, since a revert is always a person's act (decision 10).
     */
    public Mono<IdempotentCommands.Outcome> revert(AccountScope scope, String findingId, Decision decision, String idempotencyKey, String requestId,
            String relayedAssertion, OnBehalfOfPort.Person person) {
        Decision d = decision == null ? Decision.NONE : decision;
        return idempotent.run(scope.accountId(), idempotencyKey, Map.of("op", "revert", "findingId", findingId, "decision", d), 202,
                () -> doRevert(scope, findingId, d, idempotencyKey, requestId, relayedAssertion, person).map(Json::objectNode));
    }

    public Mono<IdempotentCommands.Outcome> accept(AccountScope scope, String findingId, Decision decision, String idempotencyKey, String requestId) {
        Decision d = decision == null ? Decision.NONE : decision;
        return idempotent.run(scope.accountId(), idempotencyKey, Map.of("op", "accept", "findingId", findingId, "decision", d), 200,
                () -> doAccept(scope, findingId, d).map(Json::objectNode));
    }

    public Mono<IdempotentCommands.Outcome> ignore(AccountScope scope, String findingId, Decision decision, Instant until, String idempotencyKey,
            String requestId) {
        Decision d = decision == null ? Decision.NONE : decision;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "ignore");
        body.put("findingId", findingId);
        body.put("decision", d);
        if (until != null) {
            body.put("until", until.toString());
        }
        return idempotent.run(scope.accountId(), idempotencyKey, body, 200, () -> doIgnore(scope, findingId, d, until).map(Json::objectNode));
    }

    // revert

    private Mono<Reverted> doRevert(AccountScope scope, String findingId, Decision decision, String idempotencyKey, String requestId,
            String relayedAssertion, OnBehalfOfPort.Person person) {
        return openFinding(scope, findingId).flatMap(finding -> {
            if (finding.reverting()) {
                return Mono.error(new ConflictException("finding.reverting: a revert job is already running", Map.of("jobId", finding.revertJobId())));
            }
            return watchOf(finding).flatMap(watch -> typeOf(finding.type()).flatMap(type -> {
                List<String> stuck = DriftRules.notRevertable(finding.fields(), type.mutable());
                if (!stuck.isEmpty()) {
                    return Mono.error(new ConflictException("finding.notRevertable: the type's update workflow cannot change " + String.join(", ", stuck),
                            Map.of("fields", stuck)));
                }
                return updateWorkflow(type).zipWith(assertionFor(finding, relayedAssertion, person)).flatMap(found -> {
                    CatalogEntry entry = found.getT1();
                    String assertion = found.getT2();
                    String actor = person != null && person.subject() != null ? person.subject() : decision.actorOr(scope.principal());
                    String reason = decision.reason() != null ? decision.reason() : "revert of drift finding " + findingId;
                    ObjectNode changes = Json.object();
                    Map<String, Object> input = new LinkedHashMap<>();
                    if (entry.subjectInput() != null) {
                        input.put(entry.subjectInput(), subjectOf(watch));
                    }
                    for (FindingField f : finding.drifted()) {
                        changes.set(f.fieldName(), f.expected());
                        input.put(f.fieldName(), Json.MAPPER.convertValue(f.expected(), Object.class));
                    }
                    if (entry.input() != null && entry.input().path("properties").has("reason") && !input.containsKey("reason")) {
                        input.put("reason", reason);
                    }
                    Map<String, String> pathParams = pathParams(entry, watch);
                    String key = idempotencyKey != null && !idempotencyKey.isBlank() ? "drift:" + scope.accountId() + ":" + idempotencyKey.trim()
                            : "drift:" + findingId + ":" + finding.version();
                    RequestOptions options = RequestOptions.console(assertion, requestId, key)
                            .withResourceWfrn(watch.wfrn())
                            .withLabel(FINDING_LABEL, findingId);
                    if (entry.version() != null) {
                        options = options.withWorkflowVersion(entry.version());
                    }
                    final RequestOptions sent = options;
                    return state.requestUpdate(watch.wfrn(), entry.workflow(), entry.version(), changes, actor, requestId)
                            .doOnSuccess(r -> metrics.stateCall("request-update", "ok"))
                            .doOnError(e -> metrics.stateCall("request-update", "error"))
                            .then(starter.start(watch.accountId(), entry, pathParams, input, sent))
                            .flatMap(job -> state.noteJob(watch.wfrn(), job.jobId())
                                    .then(Mono.defer(() -> {
                                        Finding running = finding.reverting(job.jobId(), actor, reason, clock.instant());
                                        return findings.save(running)
                                                .flatMap(saved -> running.storedAs(saved) ? events.updated(saved).thenReturn(saved) : Mono.just(saved));
                                    }))
                                    .flatMap(saved -> WatchUpdates.update(watches, watch.wfrn(), w -> w.toBuilder().tier(Tier.HOT).build())
                                            .thenReturn(saved))
                                    .doOnSuccess(saved -> {
                                        metrics.finding("revert-started", saved.severity().id());
                                        log.info("finding {}: revert of {} started as job {} by {}", findingId, watch.wfrn(), job.jobId(), actor);
                                    })
                                    .map(saved -> new Reverted(findingId, job.jobId())));
                });
            }));
        });
    }

    /** The relayed assertion, or one the local signer makes for the person; neither is a 409. */
    private Mono<String> assertionFor(Finding finding, String relayed, OnBehalfOfPort.Person person) {
        if (relayed != null && !relayed.isBlank()) {
            return Mono.just(relayed.trim());
        }
        Mono<String> signed = person == null ? Mono.empty() : onBehalfOf.assertion(finding.accountId(), finding.region(), person);
        return signed.switchIfEmpty(Mono.error(() -> new ConflictException(
                "revert.needsPerson: a revert runs on behalf of the person who asks for it; the call needs the BFF's X-On-Behalf-Of assertion",
                Map.of("header", "X-On-Behalf-Of"))));
    }

    /** The type's update workflow from the platform's catalog: verb {@code update}, the one that names its subject input first. */
    private Mono<CatalogEntry> updateWorkflow(TypeInfo type) {
        return platform.listCatalog(type.name()).flatMap(entries -> {
            Optional<CatalogEntry> best = entries.stream()
                    .filter(e -> "update".equals(e.verb()) && e.path() != null)
                    .sorted((a, b) -> Boolean.compare(a.subjectInput() == null, b.subjectInput() == null))
                    .findFirst();
            return best.map(Mono::just).orElseGet(() -> Mono.error(new ConflictException(
                    "finding.notRevertable: the type " + type.name() + " has no update workflow in the catalog", Map.of("type", type.name()))));
        });
    }

    private static String subjectOf(Watch watch) {
        return watch.registryId() != null ? watch.registryId() : watch.providerId();
    }

    /** The path's placeholders: the subject input is the platform id of the resource, anything else comes from the desired attributes. */
    private static Map<String, String> pathParams(CatalogEntry entry, Watch watch) {
        Map<String, String> params = new LinkedHashMap<>();
        Matcher m = PLACEHOLDER.matcher(entry.path());
        while (m.find()) {
            String name = m.group(1);
            if (name.equals(entry.subjectInput())) {
                params.put(name, subjectOf(watch));
            } else if (watch.desired() != null && watch.desired().hasNonNull(name)) {
                params.put(name, watch.desired().get(name).asText());
            } else {
                params.put(name, subjectOf(watch));
            }
        }
        return params;
    }

    // accept

    private Mono<Finding> doAccept(AccountScope scope, String findingId, Decision decision) {
        return openFinding(scope, findingId).flatMap(finding -> {
            if (finding.reverting()) {
                return Mono.error(new ConflictException("finding.reverting: a revert job is running; wait for it or let it fail", Map.of("jobId", finding.revertJobId())));
            }
            String actor = decision.actorOr(scope.principal());
            Instant now = clock.instant();
            return resolveInState(finding, Resolution.ACCEPTED, actor)
                    .flatMap(resource -> resource.map(r -> sync.upsert(r).then()).orElse(Mono.empty()))
                    .then(clearWatch(finding.wfrn(), null))
                    .then(close(finding.closed(Resolution.ACCEPTED, actor, decision.reason(), now, null)))
                    .doOnSuccess(saved -> {
                        metrics.finding("accepted", saved.severity().id());
                        log.info("finding {}: accepted by {}; the observed values are the desired ones now", findingId, actor);
                    });
        });
    }

    // ignore

    private Mono<Finding> doIgnore(AccountScope scope, String findingId, Decision decision, Instant until) {
        return openFinding(scope, findingId).flatMap(finding -> {
            String actor = decision.actorOr(scope.principal());
            Instant now = clock.instant();
            if (until != null && !until.isAfter(now)) {
                return Mono.error(new IllegalArgumentException("until is in the past"));
            }
            return resolveInState(finding, Resolution.IGNORED, actor)
                    .then(clearWatch(finding.wfrn(), w -> {
                        List<IgnoredField> ignored = new ArrayList<>(w.ignored());
                        String digest = w.desiredDigest();
                        for (FindingField f : finding.drifted()) {
                            ignored.removeIf(i -> i.path().equals(f.path()));
                            ignored.add(new IgnoredField(f.path(), digest, until, actor, now));
                        }
                        return ignored;
                    }))
                    .then(close(finding.closed(Resolution.IGNORED, actor, decision.reason(), now, until)))
                    .doOnSuccess(saved -> {
                        metrics.finding("ignored", saved.severity().id());
                        log.info("finding {}: {} field(s) ignored by {}{}", findingId, finding.drifted().size(), actor, until == null ? "" : " until " + until);
                    });
        });
    }

    // helpers

    /** Stores the closed finding and announces it; a finding that ended another way meanwhile is a 409. */
    private Mono<Finding> close(Finding closed) {
        return findings.save(closed).flatMap(saved -> {
            if (closed.storedAs(saved)) {
                return events.closed(saved).thenReturn(saved);
            }
            return Mono.error(new ConflictException("finding.closed: the finding was closed as "
                    + (saved.resolution() == null ? "another decision" : saved.resolution().id()) + " meanwhile",
                    saved.resolution() == null ? Map.of() : Map.of("resolution", saved.resolution().id())));
        });
    }

    private Mono<Finding> openFinding(AccountScope scope, String findingId) {
        return findings.find(scope, findingId)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("finding", findingId)))
                .flatMap(f -> f.open() ? Mono.just(f)
                        : Mono.error(new ConflictException("finding.closed: the finding was closed as " + f.resolution().id(),
                                Map.of("resolution", f.resolution().id()))));
    }

    private Mono<Watch> watchOf(Finding finding) {
        return watches.find(finding.wfrn()).switchIfEmpty(Mono.defer(() -> sync.refresh(finding.wfrn())))
                .switchIfEmpty(Mono.error(() -> new ConflictException("finding.unwatched: the resource is no longer in the inventory",
                        Map.of("wfrn", finding.wfrn()))));
    }

    private Mono<TypeInfo> typeOf(String name) {
        return types.find(name).switchIfEmpty(Mono.error(() -> new ConflictException("finding.notRevertable: the type " + name + " is unknown to the platform",
                Map.of("type", name))));
    }

    /** Resolves the drift on the state service; a state that does not know the finding is not an error here. */
    private Mono<Optional<com.iaf.console.drift.drift.domain.StateResource>> resolveInState(Finding finding, Resolution how, String actor) {
        return state.resolveDrift(finding.wfrn(), finding.findingId(), how, actor)
                .doOnSuccess(r -> metrics.stateCall("resolve-drift", "ok"))
                .map(Optional::of)
                .onErrorResume(StateServicePort.StateError.class, e -> {
                    if (e.conflict() || e.notFound()) {
                        metrics.stateCall("resolve-drift", e.error());
                        log.warn("finding {}: the state service did not resolve the drift ({}); the finding closes here", finding.findingId(), e.error());
                        return Mono.just(Optional.empty());
                    }
                    metrics.stateCall("resolve-drift", "error");
                    return Mono.error(e);
                });
    }

    /** The watch forgets the finding and is due now; the patch, when given, replaces its silenced fields. */
    private Mono<Void> clearWatch(String wfrn, java.util.function.Function<Watch, List<IgnoredField>> ignoredPatch) {
        return WatchUpdates.update(watches, wfrn, w -> {
            Watch.Builder b = w.toBuilder().openFindingId(null).nextCheckAt(clock.instant()).tier(Tier.HOT).lastDriftAt(clock.instant());
            if (ignoredPatch != null) {
                b.ignored(ignoredPatch.apply(w));
            }
            return b.build();
        }).then();
    }

    /** The desired value of a field as JSON, for callers that render the input. */
    static JsonNode desired(Watch watch, String field) {
        return watch.desired() == null ? null : watch.desired().get(field);
    }

    static Set<String> fieldNames(List<FindingField> fields) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (FindingField f : fields) {
            names.add(f.fieldName());
        }
        return names;
    }
}
