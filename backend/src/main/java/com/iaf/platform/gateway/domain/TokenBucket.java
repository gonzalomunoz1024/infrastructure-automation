package com.iaf.platform.gateway.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * A token bucket: holds at most {@code capacity} tokens, refills at {@code perMinute} tokens a
 * minute, starts full. Taking a token either succeeds or says how long until one is there. Not
 * thread-safe on its own; the caller synchronizes. Pure, no Spring.
 */
public final class TokenBucket {

    private static final double NANOS_PER_MINUTE = 60_000_000_000d;

    private final int capacity;
    private final int perMinute;
    private double tokens;
    private Instant last;

    public TokenBucket(int perMinute, int capacity, Instant now) {
        this(perMinute, capacity, capacity, now);
    }

    private TokenBucket(int perMinute, int capacity, double tokens, Instant last) {
        if (perMinute < 1 || capacity < 1) {
            throw new IllegalArgumentException("A quota needs at least one start per minute and a burst of at least one");
        }
        this.perMinute = perMinute;
        this.capacity = capacity;
        this.tokens = Math.max(0d, Math.min(capacity, tokens));
        this.last = last;
    }

    /**
     * A bucket as a store kept it: {@code tokens} as of {@code last}, clamped to the capacity of
     * the limit it is restored under (a limit lowered since never leaves more than it allows).
     */
    public static TokenBucket restore(int perMinute, int capacity, double tokens, Instant last) {
        return new TokenBucket(perMinute, capacity, tokens, last);
    }

    /** Takes one token: empty when it was there, else the wait until it will be. */
    public Optional<Duration> take(Instant now) {
        refill(now);
        if (tokens >= 1d) {
            tokens -= 1d;
            return Optional.empty();
        }
        double missing = 1d - tokens;
        long nanos = (long) Math.ceil(missing * NANOS_PER_MINUTE / perMinute);
        return Optional.of(Duration.ofNanos(Math.max(1, nanos)));
    }

    /** Returns a token taken for a request that turned out to be rejected; never above the capacity. */
    public void giveBack() {
        tokens = Math.min(capacity, tokens + 1d);
    }

    /** Tokens there now (refilled to {@code now}). */
    public double available(Instant now) {
        refill(now);
        return tokens;
    }

    /** Tokens as of {@link #last()}, without refilling: what a store keeps. */
    public double tokens() {
        return tokens;
    }

    /** When the bucket was last refilled: what a store keeps beside {@link #tokens()}. */
    public Instant last() {
        return last;
    }

    public int capacity() {
        return capacity;
    }

    public int perMinute() {
        return perMinute;
    }

    private void refill(Instant now) {
        if (now == null || !now.isAfter(last)) {
            return;
        }
        double elapsed = Duration.between(last, now).toNanos();
        tokens = Math.min(capacity, tokens + elapsed * perMinute / NANOS_PER_MINUTE);
        last = now;
    }
}
