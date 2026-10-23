package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** What a ready, running, deleting or failed step does to its child. */
public enum StepAction {
    CREATE("create"),
    DELETE("delete");

    private final String wire;

    StepAction(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The value a string names, or null. */
    public static StepAction of(String wire) {
        for (StepAction v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
