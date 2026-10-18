package com.iaf.platform.gateway.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.gateway.domain.TokenBucket;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Per-team quotas on job starts: one token bucket per team, {@code platform.gateway.quotas}
 * sets the default and the per-team overrides. Every request that starts a job takes a token
 * from its caller's team before anything else is checked: one job, or one batch whatever its
 * size (a batch's rows already roll out in waves under {@code maxParallel}, so they are not
 * charged again). The caller's team is their first team; a caller in no team has a bucket of
 * their own. Jobs the platform starts itself (triggers, child workflows, reruns) are not charged.
 * An empty bucket is a 429 whose {@code Retry-After} says when the next token comes.
 */
@Slf4j
@Service
public class JobQuotas {

    private final GatewayProperties.Quotas config;
    private final Clock clock;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public JobQuotas(GatewayProperties props, Clock clock) {
        this(props.getQuotas(), clock);
    }

    public JobQuotas(GatewayProperties.Quotas config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    /** No limits at all, for wiring that has no quotas. */
    public static JobQuotas unlimited() {
        GatewayProperties.Quotas off = new GatewayProperties.Quotas();
        off.setEnabled(false);
        return new JobQuotas(off, Clock.systemUTC());
    }

    /** One job start (or one batch) for the caller's team; a {@link TooManyRequestsException} when its bucket is empty. */
    public Mono<Void> take(Identity who) {
        return Mono.fromRunnable(() -> takeNow(who));
    }

    void takeNow(Identity who) {
        if (!config.isEnabled() || who == null || who.kind() == Identity.Kind.SYSTEM) {
            return;
        }
        String bucket = bucketOf(who);
        TokenBucket b = buckets.computeIfAbsent(bucket, this::newBucket);
        Optional<Duration> wait;
        synchronized (b) {
            wait = b.take(clock.instant());
        }
        if (wait.isPresent()) {
            TooManyRequestsException e = new TooManyRequestsException(message(who, bucket, b, wait.get()), wait.get());
            log.info("quota: {} refused a job start for {} ({} s)", bucket, who.id(), e.retryAfterSeconds());
            throw e;
        }
    }

    /** The bucket a caller draws from: their first team, else a bucket of their own. */
    public static String bucketOf(Identity who) {
        if (who.teams() != null) {
            for (String t : who.teams()) {
                if (t != null && !t.isBlank()) {
                    return t;
                }
            }
        }
        return "user:" + who.id();
    }

    /** The limit of a bucket: the team's override where it sets one, else the default. */
    TokenBucket newBucket(String bucket) {
        int perMinute = config.getPerMinute();
        int burst = config.getBurst();
        GatewayProperties.Limit override = config.getTeams() == null ? null : config.getTeams().get(bucket);
        if (override != null) {
            perMinute = override.getPerMinute() == null ? perMinute : override.getPerMinute();
            burst = override.getBurst() == null ? burst : override.getBurst();
        }
        return new TokenBucket(Math.max(1, perMinute), Math.max(1, burst), clock.instant());
    }

    private static String message(Identity who, String bucket, TokenBucket b, Duration wait) {
        long seconds = Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));
        String whose = bucket.startsWith("user:") ? who.id() + " (in no team)" : "Team " + bucket;
        return whose + " has used its quota of " + b.perMinute() + " job starts a minute (" + b.capacity()
                + " at once); try again in " + seconds + " s";
    }
}
