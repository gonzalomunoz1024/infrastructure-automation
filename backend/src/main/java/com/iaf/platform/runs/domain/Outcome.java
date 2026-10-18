package com.iaf.platform.runs.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.JobContext.Registered;

/**
 * How a walk ended: succeeded with the evaluated {@code outputs}; failed (or rolled back, when
 * compensation ran) at a step with an error class; cancelled. {@code registered} lists the
 * resources the walk's register steps recorded, so an engine that runs a child out of process
 * (a Temporal child workflow) still hands the parent what the child built; see
 * {@link ChildResults}.
 */
public record Outcome(JobStatus status, JsonNode output, String errorClass, String failedStep, String message, List<Registered> registered) {

    public Outcome {
        registered = registered == null ? List.of() : List.copyOf(registered);
    }

    public Outcome(JobStatus status, JsonNode output, String errorClass, String failedStep, String message) {
        this(status, output, errorClass, failedStep, message, List.of());
    }

    public static Outcome succeeded(JsonNode output) {
        return new Outcome(JobStatus.SUCCEEDED, output, null, null, null);
    }

    public static Outcome failed(StepFailure f, boolean compensated) {
        return new Outcome(compensated ? JobStatus.ROLLED_BACK : JobStatus.FAILED, null, f.errorClass(), f.step(), f.getMessage());
    }

    public static Outcome cancelled() {
        return new Outcome(JobStatus.CANCELLED, null, null, null, null);
    }

    /** The same outcome carrying what the walk registered. */
    public Outcome withRegistered(List<Registered> registered) {
        return new Outcome(status, output, errorClass, failedStep, message, registered);
    }

    public boolean succeeded() {
        return status == JobStatus.SUCCEEDED;
    }
}
