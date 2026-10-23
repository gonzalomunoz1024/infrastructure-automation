package com.iaf.console.fake.quotas.application;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.quotas.domain.TokenBucket;

/**
 * The job-start quotas (P-10), one bucket per console account under a delegation and per team
 * otherwise, and the observe rate buckets of P-09, per provider with a share per account.
 */
public final class Quotas {

    /** The platform's default job-start quota: 60 a minute with a burst of 60. */
    public static final int DEFAULT_LIMIT = 60;
    public static final int DEFAULT_BURST = 60;
    /** The default observe rate: 5 reads a second, with a short burst. */
    public static final int OBSERVE_LIMIT = 300;
    public static final int OBSERVE_BURST = 50;
    /** The share of a provider's observe rate one account may use while others wait. */
    public static final double ACCOUNT_SHARE = 0.25;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final FakeClock clock;

    public Quotas(FakeClock clock) {
        this.clock = clock;
    }

    /** The bucket key a job start draws from: the console account under a delegation, else the first team, else the caller. */
    public static String jobKey(Caller caller) {
        if (caller.accountId() != null) {
            return "account:" + caller.accountId();
        }
        if (!caller.teams().isEmpty()) {
            return caller.teams().get(0);
        }
        return caller.id();
    }

    /** Charges one job start to the caller's bucket; 429 when it is empty. */
    public void chargeJobStart(Caller caller) {
        String key = jobKey(caller);
        int wait = bucket(key, DEFAULT_LIMIT, DEFAULT_BURST).take(clock.now());
        if (wait > 0) {
            throw ApiException.tooManyRequests("quota exceeded for " + key, wait);
        }
    }

    /** Charges one observe read to the provider's bucket and the account's share of it; 429 when either is empty. */
    public void chargeObserve(String provider, String region, String accountId) {
        int wait = bucket("observe:" + provider + ":" + region, OBSERVE_LIMIT, OBSERVE_BURST).take(clock.now());
        if (wait == 0 && accountId != null) {
            wait = bucket("observe:" + provider + ":" + region + ":" + accountId, (int) Math.ceil(OBSERVE_LIMIT * ACCOUNT_SHARE),
                    (int) Math.ceil(OBSERVE_BURST * ACCOUNT_SHARE)).take(clock.now());
        }
        if (wait > 0) {
            throw ApiException.tooManyRequests("observe rate exceeded for " + provider, wait);
        }
    }

    /** The bucket of a key as GET /quotas/{key} answers it, created with the defaults when nothing drew from it yet. */
    public ObjectNode get(String key) {
        return bucket(key, DEFAULT_LIMIT, DEFAULT_BURST).document(clock.now());
    }

    /** Sets an override (admin), effective at once. */
    public ObjectNode put(String key, JsonNode body) {
        JsonNode limit = body.path("limitPerMinute");
        JsonNode burst = body.path("burst");
        if (!limit.isIntegralNumber() || !burst.isIntegralNumber() || limit.asInt() < 0 || burst.asInt() < 0) {
            throw ApiException.badRequest("limitPerMinute and burst must be integers of at least 0");
        }
        TokenBucket bucket = bucket(key, DEFAULT_LIMIT, DEFAULT_BURST);
        bucket.override(limit.asInt(), burst.asInt(), clock.now());
        return bucket.document(clock.now());
    }

    /** Whether a caller may read a bucket: admins any, a console account its own. */
    public static boolean mayRead(Caller caller, String key) {
        if (caller.admin()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return key.equals("account:" + caller.consoleAccount());
        }
        return key.equals(jobKey(caller)) || caller.teams().contains(key);
    }

    private TokenBucket bucket(String key, int limit, int burst) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(k, limit, burst, clock.now(), "default"));
    }
}
