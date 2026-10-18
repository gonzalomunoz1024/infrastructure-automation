package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A child job that a {@code run} step started did not succeed. Every engine throws this one (not
 * a bare {@link StepFailure}) so that a run step with {@code onFailure: continue} can record the
 * failure as its output and let the parent go on. The child has already run its own
 * compensation by then.
 */
public class ChildFailure extends StepFailure {

    public static final String CONTINUE = "continue";
    public static final String FAIL = "fail";

    private final String jobId;
    private final JobStatus status;
    private final String failedStep;

    public ChildFailure(String step, String jobId, JobStatus status, String errorClass, String failedStep, String message, boolean fatal) {
        super(step, errorClass, message, false, fatal);
        this.jobId = jobId;
        this.status = status == null ? JobStatus.FAILED : status;
        this.failedStep = failedStep;
    }

    /** The failure of child {@code jobId} (a run of {@code workflow}) as the parent's step {@code step} sees it. */
    public static ChildFailure of(String step, String jobId, String workflow, Outcome outcome) {
        boolean cancelled = outcome.status() == JobStatus.CANCELLED;
        String errorClass = cancelled ? "cancelled" : outcome.errorClass() == null ? "child_failed" : outcome.errorClass();
        String message = "Child job " + jobId + " (" + workflow + ") " + (cancelled ? "was cancelled" : outcome.status().json())
                + (outcome.message() == null ? "" : ": " + outcome.message());
        return new ChildFailure(step, jobId, outcome.status(), errorClass, outcome.failedStep(), message, cancelled);
    }

    public String jobId() {
        return jobId;
    }

    public JobStatus status() {
        return status;
    }

    /** The child's step that failed, when known. */
    public String failedStep() {
        return failedStep;
    }

    /**
     * The run step's output when the parent goes on:
     * {@code { jobId, status, outputs: {}, error: { step, message, class } }}, where
     * {@code error.step} is the child's step that failed.
     */
    public JsonNode toOutput() {
        JsonNodeFactory f = JsonNodeFactory.instance;
        ObjectNode out = f.objectNode();
        out.put("jobId", jobId);
        out.put("status", status.json());
        out.putObject("outputs");
        ObjectNode error = out.putObject("error");
        error.put("step", failedStep);
        error.put("message", getMessage());
        error.put("class", errorClass());
        return out;
    }

    /** Whether a run step's {@code onFailure} lets the parent go on. */
    public static boolean continues(String onFailure) {
        return CONTINUE.equals(onFailure);
    }
}
