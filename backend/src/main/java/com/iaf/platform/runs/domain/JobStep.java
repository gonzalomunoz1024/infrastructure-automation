package com.iaf.platform.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.StepType;

import lombok.Builder;
import lombok.With;

/** One step of a job as the API shows it: what ran, with what, what came back. */
@Builder(toBuilder = true)
@With
public record JobStep(
        String id,
        String name,
        StepType type,
        StepStatus status,
        int attempt,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        JsonNode input,
        JsonNode output,
        StepError error,
        Integer items) {

    public enum StepStatus {
        PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, SKIPPED, COMPENSATED;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static StepStatus of(String key) {
            return valueOf(key.toUpperCase().replace('-', '_'));
        }
    }

    /** Why a step failed: a class from the provider's errors table (or the platform's), a message, and whether a retry could help. */
    public record StepError(@com.fasterxml.jackson.annotation.JsonProperty("class") String errorClass, String message, boolean retryable) {
    }
}
