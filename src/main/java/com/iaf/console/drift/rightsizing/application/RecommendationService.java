package com.iaf.console.drift.rightsizing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.application.IdempotentCommands;
import com.iaf.console.drift.drift.application.InventorySync;
import com.iaf.console.drift.drift.domain.Decision;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.OnBehalfOfPort;
import com.iaf.console.drift.drift.ports.outbound.ResourceTypesPort;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;
import com.iaf.console.drift.drift.ports.outbound.WorkflowStarterPort;
import com.iaf.console.drift.rightsizing.domain.Recommendation;
import com.iaf.console.drift.rightsizing.domain.RecommendationAction;
import com.iaf.console.drift.rightsizing.domain.RecommendationStatus;
import com.iaf.console.drift.rightsizing.domain.RightsizingRules;
import com.iaf.console.drift.rightsizing.ports.outbound.RecommendationStorePort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.client.RequestOptions;
import com.iaf.console.platform.model.CatalogEntry;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The two decisions a person takes on a recommendation, each idempotent under its key. Applying one
 * works exactly as a revert does: the change is recorded at the state service, then the type's update
 * workflow (with the proposed size) or its delete workflow starts through the account's platform
 * service account on behalf of the person who asks, labelled {@code drift-recommendation}. The
 * recommendation is {@code applied} once the job is started (applyRecommendation); the next
 * utilisation pass judges the new size, and a job that failed leaves the resource to qualify again.
 * Dismissing sets it aside, for good or until a date. Nothing here runs on its own.
 */
public final class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_.-]+)}");
    /** The job label that ties a resize or delete job to its recommendation (label names are {@code [a-z0-9-]{1,32}}, P-04). */
    public static final String LABEL = "drift-recommendation";
    static final int MAX_REASON = 1000;
    private static final int ATTEMPTS = 3;

    /** The answer of an apply: the recommendation and the job that carries it out. */
    public record Applied(String recommendationId, String jobId) {
    }

    private final RecommendationStorePort store;
    private final WatchStorePort watches;
    private final ResourceTypesPort types;
    private final PlatformPort platform;
    private final StateServicePort state;
    private final WorkflowStarterPort starter;
    private final OnBehalfOfPort onBehalfOf;
    private final InventorySync sync;
    private final IdempotentCommands idempotent;
    private final String cpuField;
    private final String memoryField;
    private final Clock clock;

    public RecommendationService(RecommendationStorePort store, WatchStorePort watches, ResourceTypesPort types, PlatformPort platform,
            StateServicePort state, WorkflowStarterPort starter, OnBehalfOfPort onBehalfOf, InventorySync sync, IdempotentCommands idempotent,
            String cpuField, String memoryField, Clock clock) {
        this.store = store;
        this.watches = watches;
        this.types = types;
        this.platform = platform;
        this.state = state;
        this.starter = starter;
        this.onBehalfOf = onBehalfOf;
        this.sync = sync;
        this.idempotent = idempotent;
        this.cpuField = cpuField;
        this.memoryField = memoryField;
        this.clock = clock;
    }

    /**
     * Applies the recommendation on behalf of the person: {@code relayedAssertion} is the BFF's
     * {@code X-On-Behalf-Of} for them; without it the local signer signs for {@code person} where one
     * exists (the demo), and otherwise the call is refused, since a change is always a person's act.
     */
    public Mono<IdempotentCommands.Outcome> apply(AccountScope scope, String recommendationId, Decision decision, String idempotencyKey,
            String requestId, String relayedAssertion, OnBehalfOfPort.Person person) {
        Decision d = decision == null ? Decision.NONE : decision;
        return idempotent.run(scope.accountId(), idempotencyKey, Map.of("op", "apply-recommendation", "recommendationId", recommendationId, "decision", d),
                202, () -> doApply(scope, recommendationId, d, idempotencyKey, requestId, relayedAssertion, person).map(Json::objectNode));
    }

    /** Sets the recommendation aside, for good ({@code until} null) or until a date. */
    public Mono<IdempotentCommands.Outcome> dismiss(AccountScope scope, String recommendationId, Decision decision, Instant until, String idempotencyKey) {
        Decision d = decision == null ? Decision.NONE : decision;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("op", "dismiss-recommendation");
        body.put("recommendationId", recommendationId);
        body.put("decision", d);
        if (until != null) {
            body.put("until", until.toString());
        }
        return idempotent.run(scope.accountId(), idempotencyKey, body, 200, () -> doDismiss(scope, recommendationId, d, until).map(Json::objectNode));
    }

    // apply

    private Mono<Applied> doApply(AccountScope scope, String recommendationId, Decision decision, String idempotencyKey, String requestId,
            String relayedAssertion, OnBehalfOfPort.Person person) {
        checkReason(decision);
        return openOne(scope, recommendationId).flatMap(rec -> watchOf(rec).flatMap(watch -> {
            if (watch.inFlight()) {
                return Mono.error(new ConflictException("recommendation.changing: a change is in flight on the resource", Map.of("state", watch.state())));
            }
            ObjectNode size = RightsizingRules.sizeOf(watch.desired(), cpuField, memoryField);
            if (rec.current() != null && !RightsizingRules.sameSize(rec.current(), size)) {
                // the resource changed size since the pass: the recommendation is about a size it no longer has
                return supersede(rec).then(Mono.error(closed(RecommendationStatus.SUPERSEDED)));
            }
            return typeOf(rec.type())
                    .flatMap(type -> workflowFor(rec, type))
                    .zipWith(assertionFor(rec, relayedAssertion, person))
                    .flatMap(found -> start(scope, rec, watch, found.getT1(), found.getT2(), decision, idempotencyKey, requestId, person));
        }));
    }

    private Mono<Applied> start(AccountScope scope, Recommendation rec, Watch watch, CatalogEntry entry, String assertion, Decision decision,
            String idempotencyKey, String requestId, OnBehalfOfPort.Person person) {
        String id = rec.recommendationId();
        String actor = person != null && person.subject() != null ? person.subject() : decision.actorOr(scope.principal());
        String reason = decision.reason() != null && !decision.reason().isBlank() ? decision.reason() : defaultReason(rec);
        Map<String, Object> input = new LinkedHashMap<>();
        if (entry.subjectInput() != null) {
            input.put(entry.subjectInput(), subjectOf(watch));
        }
        if (rec.action() == RecommendationAction.RESIZE && rec.proposed() != null) {
            rec.proposed().properties().forEach(e -> input.put(e.getKey(), Json.MAPPER.convertValue(e.getValue(), Object.class)));
        }
        if (entry.input() != null && entry.input().path("properties").has("reason") && !input.containsKey("reason")) {
            input.put("reason", reason);
        }
        String key = idempotencyKey != null && !idempotencyKey.isBlank() ? "rightsizing:" + Json.sha256(scope.accountId() + ":" + idempotencyKey.trim())
                : "rightsizing:" + id;
        RequestOptions options = RequestOptions.console(assertion, requestId, key).withResourceWfrn(watch.wfrn()).withLabel(LABEL, id);
        if (entry.version() != null) {
            options = options.withWorkflowVersion(entry.version());
        }
        RequestOptions sent = options;
        Mono<StateResource> recorded = rec.action() == RecommendationAction.RESIZE
                ? state.requestUpdate(watch.wfrn(), entry.workflow(), entry.version(), rec.proposed().deepCopy(), actor, requestId)
                : state.requestDelete(watch.wfrn(), entry.workflow(), reason, actor, requestId);
        return recorded
                .onErrorResume(StateServicePort.StateError.class, e -> {
                    if (e.conflict()) {
                        return Mono.error(new ConflictException("recommendation.changing: the state service has a change in flight on the resource ("
                                + e.error() + ")", e.detail()));
                    }
                    if (e.notFound() || e.status() == 410) {
                        return supersede(rec).then(Mono.error(closed(RecommendationStatus.SUPERSEDED)));
                    }
                    return Mono.error(e);
                })
                // the watch shows the change in flight at once, so a second apply and the passes leave it alone
                .flatMap(resource -> sync.upsert(resource).then().onErrorResume(e -> {
                    log.warn("recommendation {}: the watch of {} could not be updated now: {}", id, watch.wfrn(), e.toString());
                    return Mono.empty();
                }))
                .then(Mono.defer(() -> starter.start(watch.accountId(), entry, pathParams(entry, watch), input, sent)))
                .flatMap(job -> state.noteJob(watch.wfrn(), job.jobId())
                        .then(markApplied(rec, job.jobId(), actor, reason))
                        .doOnSuccess(saved -> log.info("recommendation {}: {} of {} started as job {} by {}", id, rec.action().id(), watch.wfrn(),
                                job.jobId(), actor))
                        .thenReturn(new Applied(id, job.jobId())));
    }

    /** Applied as soon as the job is started; a pass that wrote meanwhile is read again, since the job runs either way. */
    private Mono<Recommendation> markApplied(Recommendation rec, String jobId, String actor, String reason) {
        AtomicReference<Recommendation> latest = new AtomicReference<>(rec);
        return Mono.defer(() -> store.save(latest.get().applied(jobId, actor, reason, clock.instant())))
                .onErrorResume(RecommendationStorePort.Conflict.class, e -> store.findAny(rec.recommendationId()).doOnNext(latest::set).then(Mono.error(e)))
                .retryWhen(Retry.max(ATTEMPTS).filter(RecommendationStorePort.Conflict.class::isInstance));
    }

    /** The relayed assertion, or one the local signer makes for the person; neither is a 409 {@code revert.needsPerson}. */
    private Mono<String> assertionFor(Recommendation rec, String relayed, OnBehalfOfPort.Person person) {
        if (relayed != null && !relayed.isBlank()) {
            return Mono.just(relayed.trim());
        }
        Mono<String> signed = person == null ? Mono.empty() : onBehalfOf.assertion(rec.accountId(), rec.region(), person);
        return signed.switchIfEmpty(Mono.error(() -> new ConflictException(
                "revert.needsPerson: applying a recommendation runs on behalf of the person who asks for it; the call needs the BFF's X-On-Behalf-Of assertion",
                Map.of("header", "X-On-Behalf-Of"))));
    }

    /**
     * The type's workflow from the catalog: verb {@code update} for a resize (and it must set every
     * proposed field), {@code delete} for a delete; the one that names its subject input first.
     */
    private Mono<CatalogEntry> workflowFor(Recommendation rec, TypeInfo type) {
        String verb = rec.action() == RecommendationAction.RESIZE ? "update" : "delete";
        return platform.listCatalog(type.name()).flatMap(entries -> {
            Optional<CatalogEntry> best = entries.stream()
                    .filter(e -> verb.equals(e.verb()) && e.path() != null)
                    .sorted((a, b) -> Boolean.compare(a.subjectInput() == null, b.subjectInput() == null))
                    .findFirst();
            if (best.isEmpty()) {
                return Mono.error(notApplicable("the type " + type.name() + " has no " + verb + " workflow in the catalog", Map.of("type", type.name())));
            }
            if (rec.action() == RecommendationAction.RESIZE) {
                List<String> stuck = new ArrayList<>();
                rec.proposed().fieldNames().forEachRemaining(field -> {
                    if (!sets(best.get(), field)) {
                        stuck.add(field);
                    }
                });
                if (!stuck.isEmpty()) {
                    return Mono.error(notApplicable("the type's update workflow does not set " + String.join(", ", stuck), Map.of("fields", stuck)));
                }
            }
            return Mono.just(best.get());
        });
    }

    /** Whether the update workflow sets the field: its input has it, or the catalog lists it as mutable. */
    private static boolean sets(CatalogEntry entry, String field) {
        return entry.input() != null && entry.input().path("properties").has(field) || entry.mutableFields() != null && entry.mutableFields().contains(field);
    }

    private static String defaultReason(Recommendation rec) {
        if (rec.action() == RecommendationAction.DELETE) {
            return "rightsizing recommendation " + rec.recommendationId() + ": idle, CPU p95 " + rec.utilisation().cpuP95() + "% and memory p95 "
                    + rec.utilisation().memoryP95() + "%";
        }
        return "rightsizing recommendation " + rec.recommendationId() + ": oversized, resize from " + rec.current() + " to " + rec.proposed();
    }

    // dismiss

    private Mono<Recommendation> doDismiss(AccountScope scope, String recommendationId, Decision decision, Instant until) {
        checkReason(decision);
        if (until != null && !until.isAfter(clock.instant())) {
            return Mono.error(new IllegalArgumentException("until is in the past"));
        }
        String actor = decision.actorOr(scope.principal());
        return Mono.defer(() -> openOne(scope, recommendationId)
                        .flatMap(rec -> store.save(rec.dismissed(actor, decision.reason(), until, clock.instant()))))
                .retryWhen(Retry.max(ATTEMPTS).filter(RecommendationStorePort.Conflict.class::isInstance))
                .doOnSuccess(saved -> log.info("recommendation {}: dismissed by {}{}", recommendationId, actor, until == null ? " for good" : " until " + until));
    }

    // helpers

    private Mono<Recommendation> openOne(AccountScope scope, String recommendationId) {
        return store.find(scope, recommendationId)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("recommendation", recommendationId)))
                .flatMap(r -> r.status() == RecommendationStatus.OPEN ? Mono.just(r) : Mono.error(closed(r.status())));
    }

    /** The resource's watch; one that left the inventory supersedes the recommendation, a 409. */
    private Mono<Watch> watchOf(Recommendation rec) {
        return watches.find(rec.wfrn())
                .switchIfEmpty(Mono.defer(() -> sync.refresh(rec.wfrn()).onErrorResume(StateServicePort.StateError.class,
                        e -> e.notFound() || e.status() == 410 ? Mono.empty() : Mono.error(e))))
                .switchIfEmpty(Mono.defer(() -> supersede(rec).then(Mono.error(closed(RecommendationStatus.SUPERSEDED)))));
    }

    private Mono<TypeInfo> typeOf(String name) {
        return types.find(name).switchIfEmpty(Mono.error(() -> notApplicable("the type " + name + " is unknown to the platform", Map.of("type", name))));
    }

    /** Supersedes the recommendation; one another writer changed meanwhile is left as that writer left it. */
    private Mono<Void> supersede(Recommendation rec) {
        return store.save(rec.superseded(clock.instant()))
                .doOnSuccess(saved -> log.info("recommendation {}: superseded, {} changed size or left the inventory", rec.recommendationId(), rec.wfrn()))
                .onErrorResume(RecommendationStorePort.Conflict.class, e -> Mono.empty())
                .then();
    }

    private static ConflictException closed(RecommendationStatus status) {
        return new ConflictException("recommendation.closed: the recommendation is " + status.id(), Map.of("status", status.id()));
    }

    private static ConflictException notApplicable(String why, Map<String, ?> detail) {
        return new ConflictException("recommendation.notApplicable: " + why, detail);
    }

    private static void checkReason(Decision decision) {
        if (decision.reason() != null && decision.reason().length() > MAX_REASON) {
            throw new IllegalArgumentException("reason is at most " + MAX_REASON + " characters");
        }
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
}
