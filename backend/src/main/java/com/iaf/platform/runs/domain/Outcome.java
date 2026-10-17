package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * How a walk ended: succeeded with the evaluated {@code outputs}; failed (or rolled back, when
 * compensation ran) at a step with an error class; cancelled.
 */
public record Outcome(JobStatus status, JsonNode output, String errorClass, String failedStep, String message) {

    public static Outcome succeeded(JsonNode output) {
        return new Outcome(JobStatus.SUCCEEDED, output, null, null, null);
    }

    public static Outcome failed(StepFailure f, boolean compensated) {
        return new Outcome(compensated ? JobStatus.ROLLED_BACK : JobStatus.FAILED, null, f.errorClass(), f.step(), f.getMessage());
    }

    public static Outcome cancelled() {
        return new Outcome(JobStatus.CANCELLED, null, null, null, null);
    }

    public boolean succeeded() {
        return status == JobStatus.SUCCEEDED;
    }
}
