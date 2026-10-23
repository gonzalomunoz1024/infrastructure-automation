package com.iaf.console.state.inventory.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.domain.ResourceEvent;
import com.iaf.console.state.inventory.domain.ResourceFold;
import com.iaf.console.state.inventory.ports.outbound.ResourceEventStorePort;
import com.iaf.console.state.inventory.ports.outbound.ResourceProjectionPort;

import reactor.core.publisher.Mono;

/**
 * Loads the current resource: the projection, plus any events the projection has not seen yet
 * (a crash between an append and its projection leaves such a gap), folded on top and written
 * back. So a command always decides on the whole log, and the projection heals as it is read.
 */
public final class ResourceLoader {

    private static final Logger log = LoggerFactory.getLogger(ResourceLoader.class);
    /** How many events one catch-up reads at a time. */
    static final int BATCH = 500;

    private final ResourceEventStorePort events;
    private final ResourceProjectionPort projection;

    public ResourceLoader(ResourceEventStorePort events, ResourceProjectionPort projection) {
        this.events = events;
        this.projection = projection;
    }

    /** The resource as of its last event, a tombstone included; empty when the wfrn is unknown in the scope. */
    public Mono<Resource> load(AccountScope scope, String wfrn) {
        return projection.find(scope, wfrn)
                .flatMap(stored -> catchUp(scope, stored, stored.version()))
                .switchIfEmpty(Mono.defer(() -> catchUp(scope, null, 0)));
    }

    private Mono<Resource> catchUp(AccountScope scope, Resource from, long afterVersion) {
        return events.findEvents(scope, from == null ? null : from.wfrn(), afterVersion, BATCH)
                .collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return from == null ? Mono.empty() : Mono.just(from);
                    }
                    Resource folded = fold(from, list);
                    log.info("projection of {} lagged {} event(s) behind the log; repaired to version {}", folded.wfrn(), list.size(),
                            folded.version());
                    Mono<Resource> repaired = projection.upsert(folded).thenReturn(folded);
                    return list.size() < BATCH ? repaired : repaired.flatMap(r -> catchUp(scope, r, r.version()));
                });
    }

    /** The whole log of a resource folded up to a version, for the time-travel read. */
    public Mono<Resource> loadAsOf(AccountScope scope, String wfrn, long version) {
        return events.findEvents(scope, wfrn, 0, Integer.MAX_VALUE)
                .takeWhile(e -> e.version() <= version)
                .collectList()
                .filter(list -> !list.isEmpty())
                .map(ResourceFold::replay);
    }

    /** The resource as it was at an instant: its log folded up to the last event at or before it; empty when it did not exist yet. */
    public Mono<Resource> loadAt(AccountScope scope, String wfrn, Instant at) {
        return events.findEvents(scope, wfrn, 0, Integer.MAX_VALUE)
                .takeWhile(e -> !e.at().isAfter(at))
                .collectList()
                .filter(list -> !list.isEmpty())
                .map(ResourceFold::replay);
    }

    private static Resource fold(Resource from, List<ResourceEvent> list) {
        Resource current = from;
        for (ResourceEvent event : list) {
            current = ResourceFold.apply(current, event);
        }
        return current;
    }
}
