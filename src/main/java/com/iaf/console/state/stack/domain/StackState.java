package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The states of a stack: pending while it launches, active once every step is done, failed when a step failed and stopped it, deleting while it is torn down, deleted at the end. */
public enum StackState {
    PENDING("pending"),
    ACTIVE("active"),
    FAILED("failed"),
    DELETING("deleting"),
    DELETED("deleted");

    private final String wire;

    StackState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The value a string names, or null. */
    public static StackState of(String wire) {
        for (StackState v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
