package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** What teardown does with a resource: delete it through its type's delete workflow, or leave it in the account. */
public enum DeletionPolicy {
    DELETE("delete"),
    RETAIN("retain");

    private final String wire;

    DeletionPolicy(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The value a string names, or null. */
    public static DeletionPolicy of(String wire) {
        for (DeletionPolicy v : values()) {
            if (v.wire.equals(wire)) {
                return v;
            }
        }
        return null;
    }
}
