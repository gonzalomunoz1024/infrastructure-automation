package com.iaf.console.drift.drift.adapters.state;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.FindingField;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Observation;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.Severity;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.platform.client.PlatformException;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.platform.model.JobStatus;
import com.iaf.console.platform.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The demo's stand-in for the state service ({@code console.state.mode=embedded}): the console
 * resources of the platform's registry snapshot, held in memory with the state's own semantics
 * for what this service calls (the list with {@code updatedAfter}, observations, drift, the
 * update request that makes a resource {@code updating} until the job the revert started ends,
 * the import, and the delete request that makes it {@code deleting} until the job ends). It has
 * no platform feed, so it learns the job from {@link #noteJob} and follows it when the resource is
 * read. Nothing of it runs in production.
 */
public final class EmbeddedStateService implements StateServicePort {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedStateService.class);

    private final PlatformPort platform;
    private final Clock clock;
    private final Map<String, StateResource> resources = new ConcurrentHashMap<>();
    private final Map<String, String> jobs = new ConcurrentHashMap<>();
    private volatile boolean seeded;

    public EmbeddedStateService(PlatformPort platform, Clock clock) {
        this.platform = platform;
        this.clock = clock;
    }

    /** Reads the platform's registry once; later resources arrive through imports. */
    public Mono<Integer> seed() {
        if (seeded) {
            return Mono.just(resources.size());
        }
        return pages(null).doOnNext(r -> resources.putIfAbsent(r.wfrn(), r)).count().map(Long::intValue).doOnSuccess(n -> {
            seeded = true;
            log.info("embedded state: {} console resource(s) from the platform's registry", n);
        });
    }

    private Flux<StateResource> pages(String cursor) {
        return platform.snapshotResources(null, cursor, 500).flatMapMany(page -> {
            List<StateResource> items = new ArrayList<>();
            for (Resource r : page.items()) {
                if (r.wfrn() != null && r.accountId() != null) {
                    items.add(fromPlatform(r));
                }
            }
            Flux<StateResource> these = Flux.fromIterable(items);
            return page.hasNext() ? these.concatWith(Flux.defer(() -> pages(page.next()))) : these;
        });
    }

    private StateResource fromPlatform(Resource r) {
        Instant now = clock.instant();
        ObjectNode node = Json.object();
        node.put("wfrn", r.wfrn());
        node.put("accountId", r.accountId());
        node.put("region", r.region());
        node.put("type", r.type());
        node.put("name", r.name());
        node.put("state", "active");
        node.put("version", 1);
        node.put("registryId", r.id());
        node.put("providerId", r.providerId() != null ? r.providerId() : r.id());
        node.set("attributes", Json.objectNode(r.attributes() == null ? Map.of() : r.attributes()));
        node.put("createdAt", (r.createdAt() != null ? r.createdAt() : now).toString());
        node.put("updatedAt", (r.updatedAt() != null ? r.updatedAt() : now).toString());
        return Json.read(node, StateResource.class);
    }

    @Override
    public Mono<Page<StateResource>> listResources(String accountId, Instant updatedAfter, String after, int limit) {
        return seed().then(Flux.fromIterable(resources.values())
                .flatMap(this::settled)
                .filter(r -> accountId.equals(r.accountId()) && (updatedAfter == null || r.updatedAt().isAfter(updatedAfter)))
                .sort(Comparator.comparing(StateResource::updatedAt).thenComparing(StateResource::wfrn))
                .collectList()
                .map(all -> {
                    List<StateResource> fetched = new ArrayList<>();
                    Cursor cursor = after == null || after.isBlank() ? null : CursorPaging.decode(after);
                    for (StateResource r : all) {
                        if (cursor != null) {
                            int c = r.updatedAt().toString().compareTo(String.valueOf(cursor.sortValue()));
                            if (c < 0 || c == 0 && r.wfrn().compareTo(cursor.id()) <= 0) {
                                continue;
                            }
                        }
                        fetched.add(r);
                        if (fetched.size() > limit) {
                            break;
                        }
                    }
                    return Page.from(fetched, limit, r -> new Cursor(r.updatedAt().toString(), r.wfrn()));
                }));
    }

    @Override
    public Mono<StateResource> getResource(String wfrn) {
        return seed().then(Mono.defer(() -> Mono.justOrEmpty(resources.get(wfrn)))).flatMap(this::settled);
    }

    /**
     * A resource whose update job ended goes back to active with the requested changes applied; one
     * whose delete job succeeded is deleted, and one whose delete job failed is active again.
     */
    private Mono<StateResource> settled(StateResource r) {
        String jobId = jobs.get(r.wfrn());
        if (jobId == null || !"updating".equals(r.state()) && !"deleting".equals(r.state())) {
            return Mono.just(r);
        }
        return platform.getJob(jobId)
                .map(job -> {
                    if (!job.finished()) {
                        return r;
                    }
                    jobs.remove(r.wfrn());
                    ObjectNode node = Json.objectNode(r);
                    if ("deleting".equals(r.state())) {
                        boolean gone = job.status() == JobStatus.SUCCEEDED;
                        node.put("state", gone ? "deleted" : "active");
                        if (gone) {
                            node.put("deletedAt", clock.instant().toString());
                        }
                        node.put("lastJobId", jobId);
                        return bump(node);
                    }
                    if (job.status() == JobStatus.SUCCEEDED && r.changes() != null) {
                        ObjectNode attributes = node.withObject("/attributes");
                        attributes.setAll(r.changes());
                    }
                    node.put("state", "active");
                    node.remove("changes");
                    node.put("lastJobId", jobId);
                    return bump(node);
                })
                .onErrorResume(PlatformException.class, e -> Mono.just(r));
    }

    @Override
    public Mono<StateResource> recordObservation(String wfrn, Observation observation, String actor) {
        return existing(wfrn).map(r -> {
            ObjectNode node = Json.objectNode(r);
            node.put("observedStatus", observation.status());
            if (observation.observed() != null) {
                node.set("observed", observation.observed());
            }
            node.put("observedAt", observation.at().toString());
            return bump(node);
        });
    }

    @Override
    public Mono<StateResource> recordDrift(String wfrn, String findingId, Severity severity, List<FindingField> fields, String actor) {
        return existing(wfrn).flatMap(r -> {
            if (r.drift() != null && r.drift().findingId() != null && !r.drift().findingId().equals(findingId)) {
                ObjectNode detail = Json.object();
                detail.put("findingId", r.drift().findingId());
                return Mono.error(new StateError(409, "drift.open", detail));
            }
            ObjectNode node = Json.objectNode(r);
            ObjectNode drift = node.withObject("/drift");
            drift.put("findingId", findingId);
            if (!drift.hasNonNull("since")) {
                drift.put("since", clock.instant().toString());
            }
            drift.put("severity", severity.id());
            drift.set("fields", Json.node(fields));
            return Mono.just(bump(node));
        });
    }

    @Override
    public Mono<StateResource> resolveDrift(String wfrn, String findingId, Resolution resolution, String actor) {
        return existing(wfrn).flatMap(r -> {
            if (r.drift() == null || r.drift().findingId() == null) {
                return Mono.error(new StateError(409, "drift.none", null));
            }
            if (!r.drift().findingId().equals(findingId)) {
                ObjectNode detail = Json.object();
                detail.put("findingId", r.drift().findingId());
                return Mono.error(new StateError(409, "drift.mismatch", detail));
            }
            ObjectNode node = Json.objectNode(r);
            if (resolution == Resolution.ACCEPTED && r.observed() != null) {
                node.withObject("/attributes").setAll(r.observed());
            }
            node.remove("drift");
            return Mono.just(bump(node));
        });
    }

    @Override
    public Mono<StateResource> requestUpdate(String wfrn, String workflow, String workflowVersion, ObjectNode changes, String actor, String requestId) {
        return existing(wfrn).flatMap(r -> {
            if (!"active".equals(r.state())) {
                ObjectNode detail = Json.object();
                detail.put("state", r.state());
                return Mono.error(new StateError(409, "resource.busy", detail));
            }
            ObjectNode node = Json.objectNode(r);
            node.put("state", "updating");
            node.set("changes", changes);
            node.put("workflow", workflow);
            node.put("requestedBy", actor);
            return Mono.just(bump(node));
        });
    }

    @Override
    public Mono<StateResource> tag(String wfrn, java.util.Map<String, String> set, String actor, String onBehalfOf, String requestId,
            String idempotencyKey) {
        return existing(wfrn).map(r -> {
            ObjectNode node = Json.objectNode(r);
            ObjectNode tags = node.withObject("/tags");
            set.forEach(tags::put);
            return bump(node);
        });
    }

    @Override
    public Mono<StateResource> requestDelete(String wfrn, String workflow, String reason, String actor, String requestId) {
        return existing(wfrn).flatMap(r -> {
            if (!"active".equals(r.state())) {
                ObjectNode detail = Json.object();
                detail.put("state", r.state());
                return Mono.error(new StateError(409, "resource.busy", detail));
            }
            ObjectNode node = Json.objectNode(r);
            node.put("state", "deleting");
            node.put("workflow", workflow);
            node.put("requestedBy", actor);
            if (reason != null) {
                node.put("reason", reason);
            }
            return Mono.just(bump(node));
        });
    }

    @Override
    public Mono<Void> noteJob(String wfrn, String jobId) {
        jobs.put(wfrn, jobId);
        return Mono.empty();
    }

    @Override
    public Mono<StateResource> importResource(ImportCommand c) {
        Instant now = clock.instant();
        ObjectNode node = Json.object();
        node.put("wfrn", c.wfrn());
        node.put("accountId", com.iaf.console.wfrn.Wfrn.parse(c.wfrn()).account());
        node.put("region", c.region());
        node.put("type", c.type());
        node.put("name", c.name());
        node.put("state", "active");
        node.put("version", 1);
        node.put("registryId", c.providerId());
        node.put("providerId", c.providerId());
        node.set("attributes", c.attributes() == null ? Json.object() : c.attributes());
        node.put("source", "discover");
        node.put("createdAt", now.toString());
        node.put("updatedAt", now.toString());
        StateResource r = Json.read(node, StateResource.class);
        resources.put(r.wfrn(), r);
        return Mono.just(r);
    }

    private Mono<StateResource> existing(String wfrn) {
        return seed().then(Mono.defer(() -> Mono.justOrEmpty(resources.get(wfrn)))).switchIfEmpty(Mono.error(() -> new StateError(404, "resource.notFound", null)));
    }

    private StateResource bump(ObjectNode node) {
        node.put("version", node.path("version").asLong(0) + 1);
        node.put("updatedAt", clock.instant().toString());
        StateResource r = Json.read(node, StateResource.class);
        resources.put(r.wfrn(), r);
        return r;
    }

    /** For tests: the resource as held. */
    public StateResource held(String wfrn) {
        return resources.get(wfrn);
    }
}
