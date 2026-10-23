package com.iaf.console.fake.runs.domain;

/** A step failed; the interpreter turns it into a catch, a compensation, an attention or the job's end. */
public final class StepFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String stepId;
    private final transient StepError error;
    private final boolean aborted;

    public StepFailure(String stepId, StepError error) {
        this(stepId, error, false);
    }

    public StepFailure(String stepId, StepError error, boolean aborted) {
        super(error.message());
        this.stepId = stepId;
        this.error = error;
        this.aborted = aborted;
    }

    public String stepId() {
        return stepId;
    }

    public StepError error() {
        return error;
    }

    /** True when a person aborted the job at the paused step. */
    public boolean aborted() {
        return aborted;
    }

    public static StepFailure of(String stepId, String errorClass, String message) {
        return new StepFailure(stepId, new StepError(errorClass, message, false));
    }
}
