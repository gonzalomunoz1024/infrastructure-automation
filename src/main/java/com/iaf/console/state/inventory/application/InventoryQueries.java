package com.iaf.console.state.inventory.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.CursorPaging;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceMatching;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.domain.RevertPlan;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The query side: lists from the projection, one resource (now, as of an older version, or as it
 * was at an instant, from its log), an account as it was at an instant, a revert plan, and a
 * resource's log. Sensitive values are masked unless the caller asks to see them,
 * which is logged.
 */
public final class InventoryQueries {

    private static final Logger log = LoggerFactory.getLogger(InventoryQueries.class);

    private final ResourceLoader loader;
    private final ResourceProjectionPort projection;
    private final ResourceEventStorePort events;
    private final SensitiveData sensitive;

    public InventoryQueries(ResourceLoader loader, ResourceProjectionPort projection, ResourceEventStorePort events, SensitiveData sensitive) {
        this.loader = loader;
        this.projection = projection;
        this.events = events;
        this.sensitive = sensitive;
    }

    public Mono<Page<Resource>> list(AccountScope scope, ResourceQuery query) {
        return projection.list(scope, query).map(page -> page.map(SensitiveData::mask));
    }

    /** The resource now, or as of a version; a tombstone is answered as it is (the HTTP layer makes it a 410). */
    public Mono<Resource> get(AccountScope scope, String wfrn, Long asOf, boolean reveal) {
        return get(scope, wfrn, asOf, null, reveal);
    }

    /**
     * The resource now, as of a version, or as it was at an instant (time travel: its log folded up to
     * then; 404 {@code resource.notYet} when it did not exist yet).
     */
    public Mono<Resource> get(AccountScope scope, String wfrn, Long asOf, Instant at, boolean reveal) {
        ResourceIdentity id = identity(wfrn);
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        if (asOf != null && at != null) {
            return Mono.error(new IllegalArgumentException("asOf names a version and at an instant: give one of them"));
        }
        if (at != null) {
            return loader.loadAt(scope, wfrn, at)
                    .switchIfEmpty(Mono.defer(() -> loader.load(scope, wfrn)
                            .flatMap(r -> Mono.<Resource>error(new NotFoundException("resource.notYet: '" + wfrn + "' did not exist at " + at)))))
                    .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                    .flatMap(r -> view(scope, r, reveal));
        }
        Mono<Resource> found = asOf == null ? loader.load(scope, wfrn) : loader.loadAsOf(scope, wfrn, asOf);
        return found.switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                .flatMap(r -> view(scope, r, reveal));
    }

    /**
     * An account as it was at an instant: the resources that existed then, in wfrn order, each its log
     * folded up to then and filtered by the query as it stood (the query's sort does not apply). A
     * page may hold fewer items than its limit when some fail the filters; {@code next} says whether
     * there is more.
     */
    public Mono<Page<Resource>> listAt(AccountScope scope, ResourceQuery query, Instant at) {
        String after = query.after() == null ? null : query.after().id();
        return projection.listExistingAt(scope, query.type(), at, after, query.limit()).collectList().flatMap(candidates -> {
            boolean more = candidates.size() > query.limit();
            List<Resource> page = more ? candidates.subList(0, query.limit()) : candidates;
            String next = more ? CursorPaging.encode(page.get(page.size() - 1).wfrn(), page.get(page.size() - 1).wfrn()) : null;
            return Flux.fromIterable(page)
                    .concatMap(r -> loader.loadAt(scope, r.wfrn(), at))
                    .filter(r -> ResourceMatching.matches(query, r))
                    .map(SensitiveData::mask)
                    .collectList()
                    .map(items -> new Page<>(items, next));
        });
    }

    /**
     * "Revert to this": the changes that bring a live resource back to how it was at an instant or a
     * version. The plan is worked out on the values in clear and answered masked unless the caller
     * may reveal them.
     */
    public Mono<RevertPlan> revertPlan(AccountScope scope, String wfrn, Long version, Instant at, boolean reveal) {
        ResourceIdentity id = identity(wfrn);
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        if ((version == null) == (at == null)) {
            return Mono.error(new IllegalArgumentException("name the point to revert to: a version or an instant (at)"));
        }
        Mono<Optional<Resource>> then = (version != null ? loader.loadAsOf(scope, wfrn, version) : loader.loadAt(scope, wfrn, at))
                .map(Optional::of).defaultIfEmpty(Optional.empty());
        return loader.load(scope, wfrn)
                .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                .zipWith(then)
                .flatMap(t -> {
                    Resource storedNow = t.getT1();
                    Resource storedThen = t.getT2().orElse(null);
                    Mono<Resource> openNow = sensitive.reveal(storedNow);
                    Mono<Optional<Resource>> openThen = storedThen == null ? Mono.just(Optional.empty())
                            : sensitive.reveal(storedThen).map(Optional::of);
                    return Mono.zip(openNow, openThen).map(open -> {
                        RevertPlan plan = RevertPlan.of(open.getT1(), open.getT2().orElse(null));
                        if (reveal) {
                            log.info("sensitive values of {} revealed in a revert plan to {}", wfrn, scope.principal());
                            return plan;
                        }
                        return masked(plan, storedNow, storedThen);
                    });
                });
    }

    /** The plan with every value that is sealed in either version replaced by the mask. */
    static RevertPlan masked(RevertPlan plan, Resource storedNow, Resource storedThen) {
        if (plan.fields() == null || plan.changes() == null) {
            return plan;
        }
        ObjectNode changes = plan.changes().deepCopy();
        List<RevertPlan.FieldChange> fields = new ArrayList<>();
        for (RevertPlan.FieldChange f : plan.fields()) {
            JsonPointer pointer = JsonPointer.compile(f.path());
            boolean sealed = SensitiveData.isSealed(Json.orEmpty(storedNow.attributes()).at(pointer))
                    || (storedThen != null && SensitiveData.isSealed(Json.orEmpty(storedThen.attributes()).at(pointer)));
            if (!sealed) {
                fields.add(f);
                continue;
            }
            JsonNode mask = TextNode.valueOf(SensitiveData.MASK);
            fields.add(new RevertPlan.FieldChange(f.path(), f.now() == null ? null : mask, f.then() == null ? null : mask));
            if (changes.at(pointer.head()) instanceof ObjectNode parent && parent.has(pointer.last().getMatchingProperty())
                    && !parent.get(pointer.last().getMatchingProperty()).isNull()) {
                parent.set(pointer.last().getMatchingProperty(), mask);
            }
        }
        return new RevertPlan(plan.wfrn(), plan.from(), plan.to(), plan.possible(), plan.reason(), changes, plan.tags(), fields);
    }

    /**
     * The resource's log after a version, oldest first, each entry as the contract's {@code ResourceEvent}
     * (the {@code state.resources} envelope); 404 when the wfrn has no log in the scope.
     */
    public Mono<Page<EventEnvelope>> events(AccountScope scope, String wfrn, long afterVersion, int limit, boolean reveal) {
        ResourceIdentity id = identity(wfrn);
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        Flux<ResourceEvent> page = events.findEvents(scope, wfrn, afterVersion, limit + 1);
        return page.collectList().flatMap(list -> {
            if (list.isEmpty()) {
                if (afterVersion > 0) {
                    return projection.find(scope, wfrn).map(r -> Page.<EventEnvelope>last(List.of()))
                            .switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)));
                }
                return Mono.error(NotFoundException.of("resource", wfrn));
            }
            boolean more = list.size() > limit;
            List<ResourceEvent> kept = more ? list.subList(0, limit) : list;
            String next = more ? Long.toString(kept.get(kept.size() - 1).version()) : null;
            Flux<ResourceEvent> shown = reveal ? Flux.fromIterable(kept).concatMap(sensitive::reveal) : Flux.fromIterable(kept).map(SensitiveData::mask);
            if (reveal) {
                log.info("sensitive values of {} revealed in its log to {}", wfrn, scope.principal());
            }
            return shown.map(e -> ResourceEventPublisher.envelope(e, null)).collectList().map(items -> new Page<>(items, next));
        });
    }

    private Mono<Resource> view(AccountScope scope, Resource r, boolean reveal) {
        if (!reveal) {
            return Mono.just(SensitiveData.mask(r));
        }
        log.info("sensitive values of {} revealed to {}", r.wfrn(), scope.principal());
        return sensitive.reveal(r);
    }

    private static ResourceIdentity identity(String wfrn) {
        try {
            return ResourceIdentity.of(wfrn);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a wfrn: " + e.getMessage());
        }
    }
}
