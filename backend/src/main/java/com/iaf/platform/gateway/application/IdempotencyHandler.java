package com.iaf.platform.gateway.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.gateway.domain.IdempotencyKeys;
import com.iaf.platform.gateway.domain.KeyedRequest;
import com.iaf.platform.gateway.domain.StoredAnswer;
import com.iaf.platform.gateway.ports.outbound.IdempotencyStore;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Idempotency keys on job starts. The first request with a key runs and, when it succeeds, its
 * answer is kept for {@code platform.gateway.idempotency.ttl}; the same key with the same request
 * returns that answer again (a replay) without running anything, the same key with a different
 * request is a 409, and so is a second request while the first is still in flight. A request that
 * fails frees its key, so the caller can retry with it. Keys are scoped to the caller.
 */
@Slf4j
@Service
public class IdempotencyHandler {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IdempotencyStore store;
    private final Duration ttl;
    private final Clock clock;

    public IdempotencyHandler(IdempotencyStore store, GatewayProperties props, Clock clock) {
        this(store, props.getIdempotency().getTtl(), clock);
    }

    public IdempotencyHandler(IdempotencyStore store, Duration ttl, Clock clock) {
        this.store = store;
        this.ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(24) : ttl;
        this.clock = clock;
    }

    /** What a claim says: run the request ({@link Proceed}) or answer it again ({@link Replay}). */
    public sealed interface Claim permits Proceed, Replay {
    }

    public record Proceed(String caller, String key) implements Claim {
    }

    public record Replay(StoredAnswer answer) implements Claim {
    }

    /** A value and whether it is a replay of the first answer. */
    public record Outcome<T>(T value, boolean replayed) {
    }

    /** Claims {@code key} for this request; a 409 when the key belongs to another request or its first request is still running. */
    public Mono<Claim> claim(String caller, String key, String fingerprint) {
        return Mono.fromCallable(() -> {
            String k = IdempotencyKeys.validate(key);
            KeyedRequest mine = KeyedRequest.inFlight(caller, k, fingerprint, clock.instant(), ttl);
            Optional<KeyedRequest> held = store.claim(mine, clock.instant());
            if (held.isEmpty()) {
                return (Claim) new Proceed(caller, k);
            }
            KeyedRequest first = held.get();
            if (!first.fingerprint().equals(fingerprint)) {
                throw new ConflictException(IdempotencyKeys.HEADER + " '" + k + "' was used with a different request; use a new key for a new request");
            }
            if (!first.answered()) {
                throw new ConflictException("The first request with " + IdempotencyKeys.HEADER + " '" + k + "' is still running; retry once it has answered");
            }
            log.debug("idempotent replay of {} for {}", k, caller);
            return new Replay(first.answer());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** Keeps a successful answer for the key; any other answer frees it. */
    public Mono<Void> complete(String caller, String key, StoredAnswer answer) {
        return Mono.<Void>fromRunnable(() -> {
            if (answer != null && answer.succeeded()) {
                store.complete(caller, key, answer);
            } else {
                store.release(caller, key);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> release(String caller, String key) {
        return Mono.<Void>fromRunnable(() -> store.release(caller, key)).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Runs {@code action} once per key and fingerprint for JSON answers (MCP tool calls): a replay
     * gives back the first answer; an error frees the key and passes through.
     */
    public Mono<Outcome<JsonNode>> once(String caller, String key, String fingerprint, Supplier<Mono<JsonNode>> action) {
        return claim(caller, key, fingerprint).flatMap(claim -> {
            if (claim instanceof Replay r) {
                return Mono.fromCallable(() -> new Outcome<>(JSON.readTree(r.answer().body()), true));
            }
            Proceed p = (Proceed) claim;
            return Mono.defer(action)
                    .flatMap(value -> complete(p.caller(), p.key(), new StoredAnswer(200, Map.of("Content-Type", "application/json"), text(value)))
                            .thenReturn(new Outcome<>(value, false)))
                    .onErrorResume(e -> release(p.caller(), p.key()).then(Mono.error(e)))
                    .doOnCancel(() -> store.release(p.caller(), p.key()));
        });
    }

    private static String text(JsonNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot keep the answer: " + e.getOriginalMessage(), e);
        }
    }
}
