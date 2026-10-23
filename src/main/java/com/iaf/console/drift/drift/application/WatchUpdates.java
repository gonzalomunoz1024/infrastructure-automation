package com.iaf.console.drift.drift.application;

import java.util.function.UnaryOperator;

import com.iaf.console.drift.drift.domain.Watch;
import com.iaf.console.drift.drift.ports.outbound.WatchStorePort;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Read-modify-write of one watch. Every store write is conditional on the revision read, so a change
 * is made to the watch as it stands and retried when another writer (a scan, the sync, a decision)
 * got there first.
 */
final class WatchUpdates {

    static final int ATTEMPTS = 5;

    private WatchUpdates() {
    }

    /** Applies the change to the stored watch and answers it; empty when there is none. */
    static Mono<Watch> update(WatchStorePort watches, String wfrn, UnaryOperator<Watch> change) {
        return Mono.defer(() -> watches.find(wfrn).flatMap(w -> watches.save(change.apply(w))))
                .retryWhen(Retry.max(ATTEMPTS).filter(WatchStorePort.Conflict.class::isInstance));
    }

    /** Saves a copy read earlier, unless the watch changed meanwhile: then the other writer's version stands. */
    static Mono<Watch> saveIfUnchanged(WatchStorePort watches, Watch w) {
        return watches.save(w).onErrorResume(WatchStorePort.Conflict.class, e -> Mono.empty());
    }
}
