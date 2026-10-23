package com.iaf.console.bff.jobs.domain;

/**
 * What the platform says of a failure: the error class (from the finished job, else the step it
 * waits on, else the failed step), its message, and the failing step.
 */
public record FailureFacts(String errorClass, String message, JobExplanation.FailedStep step, boolean paused) {

    public static final String UNKNOWN = "unknown";

    public FailureFacts {
        errorClass = errorClass == null || errorClass.isBlank() ? UNKNOWN : errorClass;
        message = message == null || message.isBlank() ? null : message;
    }
}
