package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One walk of a type's provider for everything it has, the contract's {@code DiscoveryRun}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveryRun(String runId, @JsonIgnore String accountId, String type, String region, String status, Instant startedAt, Instant endedAt,
        Integer seen, Integer unmanaged, String error) {

    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";

    /** A run that starts now. */
    public static DiscoveryRun start(String runId, String accountId, String type, String region, Instant at) {
        return new DiscoveryRun(runId, accountId, type, region, RUNNING, at, null, 0, 0, null);
    }

    /** The same run, under the account the store keeps it for (the account is not part of the JSON). */
    public DiscoveryRun forAccount(String account) {
        return new DiscoveryRun(runId, account, type, region, status, startedAt, endedAt, seen, unmanaged, error);
    }

    public boolean running() {
        return RUNNING.equals(status);
    }

    public DiscoveryRun succeeded(int seenCount, int unmanagedCount, Instant at) {
        return new DiscoveryRun(runId, accountId, type, region, SUCCEEDED, startedAt, at, seenCount, unmanagedCount, null);
    }

    public DiscoveryRun failed(String why, int seenCount, int unmanagedCount, Instant at) {
        return new DiscoveryRun(runId, accountId, type, region, FAILED, startedAt, at, seenCount, unmanagedCount, why);
    }

    public DiscoveryRun progress(int seenCount, int unmanagedCount) {
        return new DiscoveryRun(runId, accountId, type, region, status, startedAt, endedAt, seenCount, unmanagedCount, error);
    }
}
