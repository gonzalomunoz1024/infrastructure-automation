package com.iaf.console.identity.common.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import reactor.core.publisher.Mono;

/** The in-memory store of the demo and the tests. */
public final class InMemoryIdempotency implements IdempotencyPort {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    @Override
    public Mono<Reservation> reserve(String scope, String bodyHash, Instant now, Duration ttl) {
        return Mono.fromSupplier(() -> {
            IdempotencyRecord fresh = new IdempotencyRecord(scope, bodyHash, null, null, null, now, now.plus(ttl));
            IdempotencyRecord[] seen = new IdempotencyRecord[1];
            IdempotencyRecord stored = records.compute(scope, (k, existing) -> {
                if (existing == null || existing.expiresAt().isBefore(now)) {
                    return fresh;
                }
                seen[0] = existing;
                return existing;
            });
            if (seen[0] == null) {
                return new Reservation(Outcome.RESERVED, stored);
            }
            if (!seen[0].bodyHash().equals(bodyHash)) {
                return new Reservation(Outcome.MISMATCH, seen[0]);
            }
            return new Reservation(seen[0].completed() ? Outcome.REPLAY : Outcome.IN_FLIGHT, seen[0]);
        });
    }

    @Override
    public Mono<Void> complete(String scope, int status, String contentType, byte[] body) {
        return Mono.fromRunnable(() -> records.computeIfPresent(scope, (k, r) -> r.completed(status, contentType, body)));
    }

    @Override
    public Mono<Void> release(String scope) {
        return Mono.fromRunnable(() -> records.remove(scope));
    }

    public int size() {
        return records.size();
    }
}
