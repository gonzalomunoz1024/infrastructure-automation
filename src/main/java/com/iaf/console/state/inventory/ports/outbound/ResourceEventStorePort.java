package com.iaf.console.state.inventory.ports.outbound;

import java.time.Instant;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.state.inventory.domain.ResourceEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The log: every event of every resource, unique on {@code (wfrn, version)}, so two writers that
 * both think they hold version n cannot both append n+1 (the second gets a 409 and starts over).
 */
public interface ResourceEventStorePort {

    /** Appends the event; a {@code ConflictException} ({@code version.conflict}) when its version is taken. */
    Mono<ResourceEvent> append(ResourceEvent event);

    /** The events of a resource after a version, in version order, at most {@code limit}. */
    Flux<ResourceEvent> findEvents(AccountScope scope, String wfrn, long afterVersion, int limit);

    /** Marks every event of a resource to expire at an instant: what a tombstone does to its log. */
    Mono<Void> expireAt(String wfrn, Instant at);

    /** How many events the log holds, for the health and the rebuild report. */
    @GlobalRead("a count of this service's own log")
    Mono<Long> countAll();
}
