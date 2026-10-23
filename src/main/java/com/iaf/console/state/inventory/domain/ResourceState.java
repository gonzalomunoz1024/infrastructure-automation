package com.iaf.console.state.inventory.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The lifecycle classes of a console resource (P-06), as the contract's {@code ResourceState}. */
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

    /** True for a resource that still exists for the console: everything but a tombstone. */
    public boolean live() {
        return this != DELETED;
    }

    /** True while a platform job is expected to end the state. */
    public boolean busy() {
        return this == PENDING || this == UPDATING || this == DELETING;
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
}
