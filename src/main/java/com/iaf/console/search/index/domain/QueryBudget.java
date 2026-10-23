package com.iaf.console.search.index.domain;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An account's query budget, a token bucket per account: {@code perSecond} queries sustained,
 * {@code burst} at once. Over it, the answer is how many seconds to wait, for {@code Retry-After}.
 */
public final class QueryBudget {

    private final double perSecond;
    private final double burst;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long atNanos;

        Bucket(double tokens, long atNanos) {
            this.tokens = tokens;
            this.atNanos = atNanos;
        }
    }

    public QueryBudget(double perSecond, double burst, Clock clock) {
        if (perSecond <= 0 || burst < 1) {
            throw new IllegalArgumentException("a budget is at least one query");
        }
        this.perSecond = perSecond;
        this.burst = burst;
        this.clock = clock;
    }

    /** Takes one query from the account's budget: 0 when it was there, else the seconds to wait (at least 1). */
    public int take(String accountId) {
        long now = nowNanos();
        Bucket bucket = buckets.computeIfAbsent(accountId, k -> new Bucket(burst, now));
        synchronized (bucket) {
            double refilled = (now - bucket.atNanos) / 1_000_000_000.0 * perSecond;
            bucket.tokens = Math.min(burst, bucket.tokens + Math.max(0, refilled));
            bucket.atNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            double wait = (1 - bucket.tokens) / perSecond;
            return (int) Math.max(1, Math.ceil(wait));
        }
    }

    /** Forgets accounts not seen for a while, so the map does not grow with every account that ever searched. */
    public void forgetIdle(long olderThanSeconds) {
        long cutoff = nowNanos() - olderThanSeconds * 1_000_000_000L;
        buckets.entrySet().removeIf(e -> e.getValue().atNanos < cutoff);
    }

    public int size() {
        return buckets.size();
    }

    private long nowNanos() {
        var t = clock.instant();
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }
}
