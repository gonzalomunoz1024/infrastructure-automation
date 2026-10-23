package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The eleven event types of {@code state.stacks}, with their PascalCase wire names. */
public enum StackEventType {
    STACK_REQUESTED("StackRequested"),
    STACK_STEP_STARTED("StackStepStarted"),
    STACK_STEP_SUCCEEDED("StackStepSucceeded"),
    STACK_STEP_FAILED("StackStepFailed"),
    STACK_STEP_RETRIED("StackStepRetried"),
    STACK_LAUNCHED("StackLaunched"),
    STACK_RESUMED("StackResumed"),
    STACK_TEARDOWN_REQUESTED("StackTeardownRequested"),
    STACK_STEP_DELETING("StackStepDeleting"),
    STACK_STEP_REMOVED("StackStepRemoved"),
    STACK_DELETED("StackDeleted");

    private final String wire;

    StackEventType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
