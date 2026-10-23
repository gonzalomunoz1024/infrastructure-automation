package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** The contract's {@code RebuildRun}: one walk of the state service's lists into the index. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RebuildRun(String runId, String accountId, Status status, Instant startedAt, Instant endedAt, long indexed, String error,
        @JsonIgnore String idempotencyKey) {

    public enum Status {
        RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        @com.fasterxml.jackson.annotation.JsonValue
        public String wire() {
            return wire;
        }
    }

    public RebuildRun {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
    }

    public static RebuildRun start(String runId, String accountId, Instant at, String idempotencyKey) {
        return new RebuildRun(runId, accountId, Status.RUNNING, at, null, 0, null, idempotencyKey);
    }

    public boolean running() {
        return status == Status.RUNNING;
    }

    public RebuildRun progressed(long indexedSoFar) {
        return new RebuildRun(runId, accountId, status, startedAt, endedAt, indexedSoFar, error, idempotencyKey);
    }

    public RebuildRun succeeded(Instant at, long total) {
        return new RebuildRun(runId, accountId, Status.SUCCEEDED, startedAt, at, total, null, idempotencyKey);
    }

    public RebuildRun failed(Instant at, long total, String why) {
        return new RebuildRun(runId, accountId, Status.FAILED, startedAt, at, total, why, idempotencyKey);
    }
}
