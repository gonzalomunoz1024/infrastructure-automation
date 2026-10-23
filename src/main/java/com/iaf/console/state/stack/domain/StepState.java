package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The states of a stack step: waiting on its dependencies, ready (announced), running (its child is being created), done (its child is active), failed, deleting (its child is being deleted), and at teardown deleted, retained or skipped (it never took a child). */
public enum StepState {
    WAITING("waiting"),
    READY("ready"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    DELETING("deleting"),
    DELETED("deleted"),
    RETAINED("retained"),
    SKIPPED("skipped");

    private final String wire;

    StepState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** True once the step has left the stack: deleted, retained or skipped. */
    public boolean removed() {
        return this == DELETED || this == RETAINED || this == SKIPPED;
    }

    /** The value a string names, or null. */
    public static StepState of(String wire) {
        for (StepState v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
