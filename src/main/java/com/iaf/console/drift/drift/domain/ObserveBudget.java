package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform's observe rate, kept on this side too (P-09): a bucket per provider and region,
 * and a share of it per account, so one account's scan leaves room for the others and the service
 * paces itself instead of collecting 429s. The platform counts provider reads, so a page is one
 * read and a batch one per resource in it; a provider whose {@code spec.limits} differ from the
 * platform's default gets its own rate here. Thread-safe; a scan asks before each call and waits,
 * or defers, when told to.
 */
public final class ObserveBudget {

    /** A provider's own rate, as its {@code spec.limits} set it on the platform. */
    public record Limit(int perMinute, int burst) {
        public Limit {
            perMinute = Math.max(1, perMinute);
            burst = Math.max(1, burst);
        }
    }

    private final Limit standard;
    private final double accountShare;
    private final Map<String, Limit> providers;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ObserveBudget(int perMinute, int burst, double accountShare) {
        this(perMinute, burst, accountShare, Map.of());
    }

    public ObserveBudget(int perMinute, int burst, double accountShare, Map<String, Limit> providers) {
        this.standard = new Limit(perMinute, burst);
        this.accountShare = accountShare <= 0 || accountShare > 1 ? 1.0 : accountShare;
        this.providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /** The provider's rate: its own, or the platform's default. */
    public Limit limitOf(String provider) {
        return providers.getOrDefault(provider, standard);
    }

    /** Takes one read for the provider, region and account; empty when it may go, else how long to wait. */
    public Optional<Duration> acquire(String provider, String region, String accountId, Instant now) {
        return acquire(provider, region, accountId, now, 1);
    }

    /**
     * Takes {@code reads} reads at once (a batch: one per resource). They may go once the bucket
     * holds them, or once it is full when they are more than it holds; what they take beyond the
     * tokens there is owed, so the reads after them wait for it.
     */
    public Optional<Duration> acquire(String provider, String region, String accountId, Instant now, int reads) {
        Limit limit = limitOf(provider);
        Bucket shared = bucket(provider + ":" + region, limit.perMinute(), limit.burst());
        Bucket own = accountId == null ? null
                : bucket(provider + ":" + region + ":" + accountId, Math.max(1, (int) Math.ceil(limit.perMinute() * accountShare)),
                        Math.max(1, (int) Math.ceil(limit.burst() * accountShare)));
        int n = Math.max(1, reads);
        synchronized (shared) {
            Duration waitShared = shared.waitFor(now, n);
            Duration waitOwn = own == null ? Duration.ZERO : own.waitFor(now, n);
            Duration wait = waitShared.compareTo(waitOwn) >= 0 ? waitShared : waitOwn;
            if (!wait.isZero()) {
                return Optional.of(wait);
            }
            shared.take(now, n);
            if (own != null) {
                own.take(now, n);
            }
            return Optional.empty();
        }
    }

    /** Tells the budget the platform refused: the bucket is empty until the platform's Retry-After. */
    public void exhausted(String provider, String region, String accountId, Instant now, Duration retryAfter) {
        Limit limit = limitOf(provider);
        Bucket b = bucket(provider + ":" + region + (accountId == null ? "" : ":" + accountId), limit.perMinute(), limit.burst());
        synchronized (b) {
            b.drain(now, retryAfter);
        }
    }

    private Bucket bucket(String key, int limit, int size) {
        return buckets.computeIfAbsent(key, k -> new Bucket(limit, size));
    }

    private static final class Bucket {
        private final double perMillisecond;
        private final int size;
        private double tokens;
        private Instant at;
        private Instant blockedUntil;

        Bucket(int perMinute, int size) {
            this.perMillisecond = perMinute / 60_000.0;
            this.size = size;
            this.tokens = size;
        }

        private void refill(Instant now) {
            if (at == null) {
                at = now;
                return;
            }
            long elapsed = Math.max(0, Duration.between(at, now).toMillis());
            tokens = Math.min(size, tokens + elapsed * perMillisecond);
            at = now;
        }

        Duration waitFor(Instant now, int reads) {
            if (blockedUntil != null) {
                if (now.isBefore(blockedUntil)) {
                    return Duration.between(now, blockedUntil);
                }
                blockedUntil = null;
            }
            refill(now);
            double needed = Math.min(size, reads);
            if (tokens >= needed) {
                return Duration.ZERO;
            }
            long millis = (long) Math.ceil((needed - tokens) / perMillisecond);
            return Duration.ofMillis(Math.max(1, millis));
        }

        void take(Instant now, int reads) {
            refill(now);
            tokens -= reads;
        }

        void drain(Instant now, Duration retryAfter) {
            refill(now);
            tokens = Math.min(tokens, 0);
            blockedUntil = now.plus(retryAfter == null ? Duration.ofSeconds(1) : retryAfter);
        }
    }
}
