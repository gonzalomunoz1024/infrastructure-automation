package com.iaf.console.search.index.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The lifecycle classes of a console resource (P-06), the contract's {@code ResourceState}. */
public enum ResourceState {
    PENDING("pending"),
    ACTIVE("active"),
    UPDATING("updating"),
    DELETING("deleting"),
    DELETED("deleted"),
    FAILED("failed");

    private final String wire;

    ResourceState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The state a string names, or null. */
    public static ResourceState of(String wire) {
        if (wire == null) {
            return null;
        }
        for (ResourceState s : values()) {
            if (s.wire.equals(wire)) {
                return s;
            }
        }
        return null;
    }

    /** The states a query may name, for an error message. */
    public static String names() {
        return "pending, active, updating, deleting, deleted, failed";
    }
}
