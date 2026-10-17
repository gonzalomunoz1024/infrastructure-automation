package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.domain.JobStep.StepError;

/**
 * A step failed. {@code errorClass} is the provider's error class ({@code not_found},
 * {@code rate_limited}), the platform's ({@code timeout}, {@code assertion_failed},
 * {@code rejected}, {@code expired}, {@code verify_failed}, {@code expression}) or the name a
 * {@code raise} step gave it. A {@code fatal} failure is never retried and never pauses the job:
 * a rejected approval, a raise, an abort.
 */
public class StepFailure extends RuntimeException {

    private final String step;
    private final String errorClass;
    private final boolean retryable;
    private final boolean fatal;

    public StepFailure(String step, String errorClass, String message, boolean retryable) {
        this(step, errorClass, message, retryable, false);
    }

    public StepFailure(String step, String errorClass, String message, boolean retryable, boolean fatal) {
        super(message == null ? errorClass : message);
        this.step = step;
        this.errorClass = errorClass == null ? "error" : errorClass;
        this.retryable = retryable;
        this.fatal = fatal;
    }

    public static StepFailure fatal(String step, String errorClass, String message) {
        return new StepFailure(step, errorClass, message, false, true);
    }

    public String step() {
        return step;
    }

    public String errorClass() {
        return errorClass;
    }

    public boolean retryable() {
        return retryable;
    }

    public boolean fatal() {
        return fatal;
    }

    public StepFailure at(String stepId) {
        return step != null ? this : new StepFailure(stepId, errorClass, getMessage(), retryable, fatal);
    }

    public StepError toError() {
        return new StepError(errorClass, getMessage(), retryable);
    }

    /** The {@code $error} value: {@code { step, message, class, retryable }}. */
    public JsonNode toJson() {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        o.put("step", step);
        o.put("message", getMessage());
        o.put("class", errorClass);
        o.put("retryable", retryable);
        return o;
    }
}
