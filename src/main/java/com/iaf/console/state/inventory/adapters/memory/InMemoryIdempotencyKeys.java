package com.iaf.console.state.inventory.adapters.memory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.state.inventory.ports.outbound.IdempotencyKeyPort;

import reactor.core.publisher.Mono;

/** Idempotency keys in memory, dropped when their time is up. */
public final class InMemoryIdempotencyKeys implements IdempotencyKeyPort {

    private record Entry(StoredResponse response, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryIdempotencyKeys(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<StoredResponse> find(String id) {
        return Mono.fromSupplier(() -> {
            Entry e = entries.get(id);
            if (e == null) {
                return null;
            }
            if (!clock.instant().isBefore(e.expiresAt())) {
                entries.remove(id, e);
                return null;
            }
            return e.response();
        });
    }

    @Override
    public Mono<StoredResponse> save(StoredResponse response, Duration ttl) {
        return Mono.fromSupplier(() -> {
            Entry fresh = new Entry(response, clock.instant().plus(ttl));
            Entry stored = entries.compute(response.id(), (k, e) -> e == null || !clock.instant().isBefore(e.expiresAt()) ? fresh : e);
            return stored.response();
        });
    }
}
