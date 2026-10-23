package com.iaf.console.bff.session.domain;

/** A guarded action was attempted on a session whose authentication is too old. */
public final class StepUpRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String action;

    public StepUpRequiredException(String action) {
        super("step-up authentication required for " + action);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
