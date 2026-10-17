package com.iaf.platform.runs.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.workflows.domain.Step;

/**
 * What the walker tells the executor about a step: it started (running), waits for a person
 * (waiting), succeeded, failed, was skipped or compensated. The executor turns it into a
 * {@code job.step} event and the projection into a {@link JobStep}.
 */
public record StepEvent(
        Step step,
        StepStatus status,
        int attempt,
        JsonNode input,
        JsonNode output,
        StepError error,
        Instant at,
        Long durationMs,
        Integer items,
        boolean replayed) {

    public String id() {
        return step.id();
    }

    public String name() {
        return step.displayName();
    }

    public static StepEvent running(Step step, int attempt, JsonNode input, Instant at) {
        return new StepEvent(step, StepStatus.RUNNING, attempt, input, null, null, at, null, null, false);
    }

    public static StepEvent waiting(Step step, int attempt, JsonNode input, Instant at) {
        return new StepEvent(step, StepStatus.WAITING, attempt, input, null, null, at, null, null, false);
    }

    public static StepEvent succeeded(Step step, int attempt, JsonNode input, JsonNode output, Instant at, long durationMs, Integer items) {
        return new StepEvent(step, StepStatus.SUCCEEDED, attempt, input, output, null, at, durationMs, items, false);
    }

    public static StepEvent replayed(Step step, JsonNode input, JsonNode output, Instant at) {
        return new StepEvent(step, StepStatus.SUCCEEDED, 1, input, output, null, at, 0L, null, true);
    }

    public static StepEvent failed(Step step, int attempt, JsonNode input, StepError error, Instant at, long durationMs) {
        return new StepEvent(step, StepStatus.FAILED, attempt, input, null, error, at, durationMs, null, false);
    }

    public static StepEvent skipped(Step step, Instant at) {
        return new StepEvent(step, StepStatus.SKIPPED, 0, null, null, null, at, null, null, false);
    }

    public static StepEvent compensated(Step step, Instant at) {
        return new StepEvent(step, StepStatus.COMPENSATED, 0, null, null, null, at, null, null, false);
    }

    public boolean ended() {
        return status == StepStatus.SUCCEEDED || status == StepStatus.FAILED || status == StepStatus.SKIPPED || status == StepStatus.COMPENSATED;
    }
}
