package com.iaf.console.state.inventory.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceIdentity;
import com.iaf.console.state.inventory.domain.ResourceQuery;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The query side: lists from the projection, one resource (now, or as of an older version from
 * its log), and a resource's log. Sensitive values are masked unless the caller asks to see them,
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
        ResourceIdentity id = identity(wfrn);
        if (!scope.covers(id.accountId(), id.region())) {
            return Mono.error(NotFoundException.of("resource", wfrn));
        }
        Mono<Resource> found = asOf == null ? loader.load(scope, wfrn) : loader.loadAsOf(scope, wfrn, asOf);
        return found.switchIfEmpty(Mono.error(() -> NotFoundException.of("resource", wfrn)))
                .flatMap(r -> view(scope, r, reveal));
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
