package com.iaf.console.drift.drift.ports.outbound;

import com.iaf.console.drift.drift.domain.Walk;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where the walks of the lists the scans page through are kept, so every shard's turn and every replica sees the one under way. */
@GlobalRead("a list's walk serves the shard loops of every replica; no person reads it")
public interface WalkStorePort {

    Mono<Walk> find(String key);

    /**
     * Writes the walk if the stored one is still at {@code walk.rev()} (0: not stored yet) and
     * answers it at the next revision; otherwise fails with {@link Conflict}: another turn took or
     * moved the walk since it was read.
     */
    Mono<Walk> save(Walk walk);

    /** Another turn wrote the walk since it was read. */
    final class Conflict extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public Conflict(String key) {
            super("the walk of " + key + " changed since it was read");
        }
    }
}
