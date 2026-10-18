package com.iaf.platform.gateway.adapters.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.iaf.platform.gateway.domain.TokenBucket;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;

/**
 * The demo quotas: one {@link TokenBucket} per team in this instance's memory, so with several
 * API replicas every replica has a bucket of its own; the {@code mongo} profile shares them.
 */
@Component
@Profile("!mongo")
public class InMemoryQuotaStore implements QuotaStore {

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Optional<Duration> take(String bucket, int perMinute, int burst, Instant now) {
        TokenBucket b = buckets.computeIfAbsent(bucket, k -> new TokenBucket(perMinute, burst, now));
        synchronized (b) {
            return b.take(now);
        }
    }

    @Override
    public void giveBack(String bucket, int burst) {
        TokenBucket b = buckets.get(bucket);
        if (b != null) {
            synchronized (b) {
                b.giveBack();
            }
        }
    }

    /** For tests: how many buckets exist. */
    int size() {
        return buckets.size();
    }
}
