package com.iaf.console.cost.ledger.domain;

import java.time.Instant;

/** A sync of the ledger with the state service: one account, or all when {@code accountId} is null. */
public record SyncRun(String runId, String accountId, String status, long resources, long corrected, Instant startedAt, Instant finishedAt,
        String error) {

    public static SyncRun started(String runId, String accountId, Instant at) {
        return new SyncRun(runId, accountId, "running", 0, 0, at, null, null);
    }

    public SyncRun succeeded(long seen, long fixed, Instant at) {
        return new SyncRun(runId, accountId, "succeeded", seen, fixed, startedAt, at, null);
    }

    public SyncRun failed(long seen, String why, Instant at) {
        return new SyncRun(runId, accountId, "failed", seen, corrected, startedAt, at, why);
    }
}
