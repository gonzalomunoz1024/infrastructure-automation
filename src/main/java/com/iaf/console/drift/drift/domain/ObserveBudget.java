package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform's observe rate, kept on this side too (P-09): a bucket per provider and region,
 * and a share of it per account, so one account's scan leaves room for the others and the service
 * paces itself instead of collecting 429s. Thread-safe; a scan asks before each read and defers a
 * group when told to wait.
 */
public final class ObserveBudget {

    private final int perMinute;
    private final int burst;
    private final double accountShare;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ObserveBudget(int perMinute, int burst, double accountShare) {
        this.perMinute = Math.max(1, perMinute);
        this.burst = Math.max(1, burst);
        this.accountShare = accountShare <= 0 || accountShare > 1 ? 1.0 : accountShare;
    }

    /** Takes one read for the provider, region and account; empty when it may go, else how long to wait. */
    public Optional<Duration> acquire(String provider, String region, String accountId, Instant now) {
        Bucket shared = bucket(provider + ":" + region, perMinute, burst);
        Bucket own = accountId == null ? null
                : bucket(provider + ":" + region + ":" + accountId, Math.max(1, (int) Math.ceil(perMinute * accountShare)),
                        Math.max(1, (int) Math.ceil(burst * accountShare)));
        synchronized (shared) {
            Duration waitShared = shared.waitFor(now);
            Duration waitOwn = own == null ? Duration.ZERO : own.waitFor(now);
            Duration wait = waitShared.compareTo(waitOwn) >= 0 ? waitShared : waitOwn;
            if (!wait.isZero()) {
                return Optional.of(wait);
            }
            shared.take(now);
            if (own != null) {
                own.take(now);
            }
            return Optional.empty();
        }
    }

    /** Tells the budget the platform refused: the bucket is empty until the platform's Retry-After. */
    public void exhausted(String provider, String region, String accountId, Instant now, Duration retryAfter) {
        Bucket b = bucket(provider + ":" + region + (accountId == null ? "" : ":" + accountId), perMinute, burst);
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

        Duration waitFor(Instant now) {
            if (blockedUntil != null) {
                if (now.isBefore(blockedUntil)) {
                    return Duration.between(now, blockedUntil);
                }
                blockedUntil = null;
            }
            refill(now);
            if (tokens >= 1) {
                return Duration.ZERO;
            }
            long millis = (long) Math.ceil((1 - tokens) / perMillisecond);
            return Duration.ofMillis(Math.max(1, millis));
        }

        void take(Instant now) {
            refill(now);
            tokens = Math.max(0, tokens - 1);
        }

        void drain(Instant now, Duration retryAfter) {
            refill(now);
            tokens = 0;
            blockedUntil = now.plus(retryAfter == null ? Duration.ofSeconds(1) : retryAfter);
        }
    }
}
