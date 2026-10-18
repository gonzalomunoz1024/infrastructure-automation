package com.iaf.platform.gateway.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * One idempotency key of one caller: the fingerprint of the request it was first used with, when,
 * until when it answers, and the answer once there is one ({@code null} while the first request is
 * still in flight).
 */
public record KeyedRequest(String caller, String key, String fingerprint, Instant createdAt, Instant expiresAt, StoredAnswer answer) {

    /** An in-flight request older than this was abandoned (its process died); the key is free again. */
    public static final Duration IN_FLIGHT_TIMEOUT = Duration.ofMinutes(5);

    public static KeyedRequest inFlight(String caller, String key, String fingerprint, Instant now, Duration ttl) {
        return new KeyedRequest(caller, key, fingerprint, now, now.plus(ttl), null);
    }

    public boolean answered() {
        return answer != null;
    }

    /** Whether the key still holds: not expired and, while in flight, not abandoned. */
    public boolean liveAt(Instant now) {
        if (!now.isBefore(expiresAt)) {
            return false;
        }
        return answered() || now.isBefore(createdAt.plus(IN_FLIGHT_TIMEOUT));
    }

    public KeyedRequest withAnswer(StoredAnswer a) {
        return new KeyedRequest(caller, key, fingerprint, createdAt, expiresAt, a);
    }
}
