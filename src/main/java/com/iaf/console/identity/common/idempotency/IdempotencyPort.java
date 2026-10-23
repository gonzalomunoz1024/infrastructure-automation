package com.iaf.console.identity.common.idempotency;

import java.time.Duration;
import java.time.Instant;

import reactor.core.publisher.Mono;

/** The store of idempotent calls, keyed by caller, operation and {@code Idempotency-Key}; entries expire after 24 hours. */
public interface IdempotencyPort {

    /** What a reservation found. */
    enum Outcome {
        /** The key is new and now reserved for this call. */
        RESERVED,
        /** The same call is still running. */
        IN_FLIGHT,
        /** The same call ended; the record holds its answer. */
        REPLAY,
        /** The key was used with another body. */
        MISMATCH
    }

    record Reservation(Outcome outcome, IdempotencyRecord record) {
    }

    /** Reserves the key for a call with this body, or says what happened to it before. */
    Mono<Reservation> reserve(String scope, String bodyHash, Instant now, Duration ttl);

    /** Stores the answer of a reserved call. */
    Mono<Void> complete(String scope, int status, String contentType, byte[] body);

    /** Frees a reservation whose call failed before it answered, so a retry may run. */
    Mono<Void> release(String scope);
}
