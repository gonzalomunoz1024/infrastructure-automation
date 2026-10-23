package com.iaf.console.drift.drift.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.ports.outbound.IdempotencyKeyPort;
import com.iaf.console.kit.errors.ConflictException;

import reactor.core.publisher.Mono;

/**
 * {@code Idempotency-Key}: the same key with the same body answers the first result for a day,
 * the same key with another body is a 409 ({@code idempotency.mismatch}). Keys are per account.
 */
public final class IdempotentCommands {

    /** What a command answered, as the HTTP layer sends it: the status, the body and whether it is a replay. */
    public record Outcome(int status, ObjectNode body, boolean replay) {
    }

    private final IdempotencyKeyPort keys;
    private final Duration ttl;
    private final Clock clock;

    public IdempotentCommands(IdempotencyKeyPort keys, Duration ttl, Clock clock) {
        this.keys = keys;
        this.ttl = ttl;
        this.clock = clock;
    }

    /** Runs the command under the key (or just runs it when there is none) and answers what the caller gets. */
    public Mono<Outcome> run(String accountId, String key, Object body, int status, Supplier<Mono<ObjectNode>> command) {
        if (key == null || key.isBlank()) {
            return Mono.defer(command).map(r -> new Outcome(status, r, false));
        }
        if (key.length() > 255) {
            return Mono.error(new IllegalArgumentException("Idempotency-Key is at most 255 characters"));
        }
        String id = accountId + ":" + key.trim();
        String hash = Json.sha256(body == null ? "" : Json.write(body));
        return keys.find(id)
                .flatMap(stored -> replay(stored, hash))
                .switchIfEmpty(Mono.defer(() -> Mono.defer(command).flatMap(r -> {
                    IdempotencyKeyPort.StoredResponse mine = new IdempotencyKeyPort.StoredResponse(id, hash, status, r, clock.instant());
                    return keys.save(mine, ttl).flatMap(stored -> stored.at().equals(mine.at()) && stored.bodyHash().equals(hash)
                            ? Mono.just(new Outcome(status, mine.body(), false))
                            : replay(stored, hash));
                })));
    }

    private static Mono<Outcome> replay(IdempotencyKeyPort.StoredResponse stored, String hash) {
        if (!stored.bodyHash().equals(hash)) {
            return Mono.error(new ConflictException("idempotency.mismatch: Idempotency-Key was used with another body",
                    Map.of("firstSeen", stored.at().toString())));
        }
        return Mono.just(new Outcome(stored.status(), stored.body(), true));
    }
}
