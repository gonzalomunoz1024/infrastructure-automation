package com.iaf.platform.runs.adapters.outbound.temporal;

import com.iaf.platform.runs.domain.StepFailure;

import io.temporal.failure.ApplicationFailure;

/**
 * A {@link StepFailure} as it crosses the activity boundary: the activity throws an
 * {@link ApplicationFailure} of type {@value #TYPE} with this record as its details, and the
 * workflow turns it back into the classified failure the interpreter handles (retries, pause on
 * failure, compensation). Non-retryable on the Temporal side: the interpreter does its own retries.
 */
public record StepFailureData(String step, String errorClass, String message, boolean retryable, boolean fatal) {

    public static final String TYPE = "StepFailure";

    public static StepFailureData of(StepFailure f) {
        return new StepFailureData(f.step(), f.errorClass(), f.getMessage(), f.retryable(), f.fatal());
    }

    public StepFailure toFailure(String fallbackStep) {
        return new StepFailure(step == null ? fallbackStep : step, errorClass, message, retryable, fatal);
    }

    public ApplicationFailure toApplicationFailure() {
        return ApplicationFailure.newNonRetryableFailure(message == null ? errorClass : message, TYPE, this);
    }
}
