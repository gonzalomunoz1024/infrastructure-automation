package com.iaf.console.bff.insights.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One console request as the executive home counts it: a top-level platform job a person started
 * through the console in one of the accounts covered. {@code selfServed} when it ended
 * succeeded with no one stepping in: no failed step retried, skipped or given a new input by
 * hand, and not itself the console's retry of an earlier failed request.
 */
public record RequestRecord(String accountId, String jobId, String workflow, String person, Outcome outcome, Instant startedAt, Long durationMs,
        boolean selfServed) {

    /** How a request ended, as far as the numbers tell them apart. */
    public enum Outcome {
        SUCCEEDED, FAILED, OTHER
    }

    public RequestRecord {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(startedAt, "startedAt");
        if (selfServed && outcome != Outcome.SUCCEEDED) {
            throw new IllegalArgumentException("only a succeeded request is self-served");
        }
    }
}
