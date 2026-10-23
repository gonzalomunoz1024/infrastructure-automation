package com.iaf.console.bff.trust.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per client of {@code POST /internal/v1/token}: {@code burst} requests at once,
 * refilled at {@code perMinute}. Per instance, which is enough to keep one client from minting
 * tokens without end; only registered clients reach it, so the buckets stay few.
 */
final class ClientRateLimits {

    private record Bucket(double tokens, Instant at) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int burst;
    private final double perNano;
    private final Clock clock;

    ClientRateLimits(int burst, int perMinute, Clock clock) {
        this.burst = Math.max(1, burst);
        this.perNano = Math.max(1, perMinute) / (double) Duration.ofMinutes(1).toNanos();
        this.clock = clock;
    }

    /** Takes a token from the client's bucket; false when it is empty. */
    boolean tryAcquire(String clientId) {
        Instant now = clock.instant();
        boolean[] taken = {false};
        buckets.compute(clientId, (id, b) -> {
            double tokens = b == null ? burst : Math.min(burst, b.tokens() + Duration.between(b.at(), now).toNanos() * perNano);
            if (tokens >= 1) {
                taken[0] = true;
                return new Bucket(tokens - 1, now);
            }
            return new Bucket(tokens, now);
        });
        return taken[0];
    }

    /** Seconds until the client's next token. */
    long retryAfterSeconds(String clientId) {
        Bucket b = buckets.get(clientId);
        double missing = b == null ? 0 : Math.max(0, 1 - b.tokens());
        return Math.max(1, (long) Math.ceil(missing / perNano / 1_000_000_000d));
    }
}
