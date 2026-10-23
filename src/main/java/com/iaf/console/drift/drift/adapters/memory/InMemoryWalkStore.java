package com.iaf.console.drift.drift.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.domain.Walk;
import com.iaf.console.drift.drift.ports.outbound.WalkStorePort;

import reactor.core.publisher.Mono;

/** The lists' walks in memory, for one replica, the demo and the tests. */
public final class InMemoryWalkStore implements WalkStorePort {

    private final Map<String, Walk> byKey = new ConcurrentHashMap<>();

    @Override
    public Mono<Walk> find(String key) {
        return Mono.justOrEmpty(byKey.get(key));
    }

    @Override
    public Mono<Walk> save(Walk walk) {
        return Mono.fromCallable(() -> {
            Walk[] saved = new Walk[1];
            byKey.compute(walk.key(), (key, current) -> {
                long stored = current == null ? 0 : current.rev();
                if (stored != walk.rev()) {
                    return current;
                }
                saved[0] = walk.withRev(stored + 1);
                return saved[0];
            });
            if (saved[0] == null) {
                throw new Conflict(walk.key());
            }
            return saved[0];
        });
    }
}
