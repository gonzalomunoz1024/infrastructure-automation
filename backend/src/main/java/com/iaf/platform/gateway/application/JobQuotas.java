package com.iaf.platform.gateway.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.gateway.adapters.outbound.InMemoryQuotaStore;
import com.iaf.platform.gateway.ports.outbound.QuotaStore;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Per-team quotas on job starts: one token bucket per team, {@code platform.gateway.quotas}
 * sets the default and the per-team overrides. Every request that starts a job takes a token
 * from its caller's team once it is known to be valid and allowed: one job, or one batch whatever
 * its size (a batch's rows already roll out in waves under {@code maxParallel}, so they are not
 * charged again). The caller's team is their first team; a caller in no team has a bucket of
 * their own. Jobs the platform starts itself (triggers, child workflows, reruns) are not charged.
 * An empty bucket is a 429 whose {@code Retry-After} says when the next token comes. The buckets
 * live in the {@link QuotaStore}: this instance's memory, or the database every replica shares.
 */
@Slf4j
@Service
public class JobQuotas {

    /** The limit of one bucket. */
    record Limit(int perMinute, int burst) {
    }

    private final GatewayProperties.Quotas config;
    private final QuotaStore store;
    private final Clock clock;

    @Autowired
    public JobQuotas(GatewayProperties props, QuotaStore store, Clock clock) {
        this(props.getQuotas(), store, clock);
    }

    public JobQuotas(GatewayProperties.Quotas config, Clock clock) {
        this(config, new InMemoryQuotaStore(), clock);
    }

    public JobQuotas(GatewayProperties.Quotas config, QuotaStore store, Clock clock) {
        this.config = config;
        this.store = store;
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
        if (!charged(who)) {
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> takeNow(who)).subscribeOn(Schedulers.boundedElastic());
    }

    void takeNow(Identity who) {
        if (!charged(who)) {
            return;
        }
        String bucket = bucketOf(who);
        Limit limit = limitOf(bucket);
        Optional<Duration> wait = store.take(bucket, limit.perMinute(), limit.burst(), clock.instant());
        if (wait.isPresent()) {
            TooManyRequestsException e = new TooManyRequestsException(message(who, bucket, limit, wait.get()), wait.get());
            log.info("quota: {} refused a job start for {} ({} s)", bucket, who.id(), e.retryAfterSeconds());
            throw e;
        }
    }

    private boolean charged(Identity who) {
        return config.isEnabled() && who != null && who.kind() != Identity.Kind.SYSTEM;
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
    Limit limitOf(String bucket) {
        int perMinute = config.getPerMinute();
        int burst = config.getBurst();
        GatewayProperties.Limit override = config.getTeams() == null ? null : config.getTeams().get(bucket);
        if (override != null) {
            perMinute = override.getPerMinute() == null ? perMinute : override.getPerMinute();
            burst = override.getBurst() == null ? burst : override.getBurst();
        }
        return new Limit(Math.max(1, perMinute), Math.max(1, burst));
    }

    private static String message(Identity who, String bucket, Limit limit, Duration wait) {
        long seconds = Math.max(1, wait.toSeconds() + (wait.toNanosPart() > 0 ? 1 : 0));
        String whose = bucket.startsWith("user:") ? who.id() + " (in no team)" : "Team " + bucket;
        return whose + " has used its quota of " + limit.perMinute() + " job starts a minute (" + limit.burst()
                + " at once); try again in " + seconds + " s";
    }
}
