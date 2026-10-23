package com.iaf.console.drift.drift.adapters.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.drift.ports.outbound.IdempotencyKeyPort;

import reactor.core.publisher.Mono;

/** Idempotency keys in memory: the first answer under a key wins and is kept for its time to live. */
public final class InMemoryIdempotencyKeys implements IdempotencyKeyPort {

    private record Kept(StoredResponse response, Instant until) {
    }

    private final Map<String, Kept> keys = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyKeys(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<StoredResponse> find(String id) {
        return Mono.fromSupplier(() -> {
            Kept kept = keys.get(id);
            if (kept == null || kept.until().isBefore(clock.instant())) {
                return null;
            }
            return kept.response();
        });
    }

    @Override
    public Mono<StoredResponse> save(StoredResponse response, Duration ttl) {
        return Mono.fromSupplier(() -> keys.compute(response.id(), (id, current) -> current != null && !current.until().isBefore(clock.instant())
                ? current
                : new Kept(response, response.at().plus(ttl))).response());
    }
}
