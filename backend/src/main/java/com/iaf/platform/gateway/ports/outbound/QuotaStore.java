package com.iaf.platform.gateway.ports.outbound;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Where the token buckets of the job quotas live, one per team (or per caller in no team). A
 * bucket is created full the first time it is drawn from; the limit ({@code perMinute} sustained
 * starts, {@code burst} at once) comes from the configuration on every call, so a changed limit
 * applies at once.
 */
public interface QuotaStore {

    /** Takes one token from {@code bucket}: empty when it was there, else the wait until it will be. */
    Optional<Duration> take(String bucket, int perMinute, int burst, Instant now);
}
