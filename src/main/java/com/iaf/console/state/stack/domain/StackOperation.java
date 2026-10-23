package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a stack is doing: launching, or being torn down. */
public enum StackOperation {
    LAUNCH("launch"),
    TEARDOWN("teardown");

    private final String wire;

    StackOperation(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The value a string names, or null. */
    public static StackOperation of(String wire) {
        for (StackOperation v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
