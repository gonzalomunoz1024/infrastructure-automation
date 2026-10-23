package com.iaf.console.state.inventory.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.state.inventory.domain.Json;
import com.iaf.console.state.inventory.domain.Resource;
import com.iaf.console.state.inventory.ports.outbound.IdempotencyKeyPort;

import reactor.core.publisher.Mono;

/**
 * {@code Idempotency-Key}: the same key with the same body answers the first result for 24 hours,
 * the same key with another body is a 409 ({@code idempotency.mismatch}). Keys are per account.
 */
public final class IdempotentCommands {

    /** What a command answered, as the HTTP layer sends it: the status, the (masked) body and whether it is a replay. */
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
    public Mono<Outcome> run(AccountScope scope, String key, String body, int status, Supplier<Mono<Resource>> command) {
        if (key == null || key.isBlank()) {
            return Mono.defer(command).map(r -> new Outcome(status, masked(r), false));
        }
        if (key.length() > 255) {
            return Mono.error(new IllegalArgumentException("Idempotency-Key is at most 255 characters"));
        }
        String id = scope.accountId() + ":" + key.trim();
        String hash = sha256(body == null ? "" : body);
        return keys.find(id)
                .flatMap(stored -> replay(stored, hash))
                .switchIfEmpty(Mono.defer(() -> Mono.defer(command).flatMap(r -> {
                    IdempotencyKeyPort.StoredResponse mine = new IdempotencyKeyPort.StoredResponse(id, hash, status, masked(r), clock.instant());
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

    private static ObjectNode masked(Resource r) {
        return Json.objectNode(SensitiveData.mask(r));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
