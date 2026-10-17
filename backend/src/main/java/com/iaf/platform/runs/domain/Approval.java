package com.iaf.platform.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Builder;
import lombok.With;

/** A human sign-off a job waits for. {@code timeout} is the duration the step allows, as written (4h, PT4H). */
@Builder(toBuilder = true)
@With
public record Approval(
        String id,
        String jobId,
        String workflow,
        String step,
        String group,
        String message,
        Instant requestedAt,
        String timeout,
        Status status,
        String decidedBy,
        Instant decidedAt,
        String comment,
        Preview preview) {

    public enum Status {
        PENDING, APPROVED, REJECTED, EXPIRED;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static Status of(String key) {
            return valueOf(key.toUpperCase());
        }
    }

    public boolean pending() {
        return status == Status.PENDING;
    }
}
