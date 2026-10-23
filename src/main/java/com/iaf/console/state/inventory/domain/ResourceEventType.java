package com.iaf.console.state.inventory.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** The thirteen event types of {@code state.resources}, with their PascalCase wire names. */
public enum ResourceEventType {
    RESOURCE_REQUESTED("ResourceRequested"),
    PROVISIONING_STARTED("ProvisioningStarted"),
    PROVISIONING_SUCCEEDED("ProvisioningSucceeded"),
    PROVISIONING_FAILED("ProvisioningFailed"),
    UPDATE_REQUESTED("UpdateRequested"),
    OBSERVED("Observed"),
    DRIFT_DETECTED("DriftDetected"),
    DRIFT_RESOLVED("DriftResolved"),
    TAGGED("Tagged"),
    DELETE_REQUESTED("DeleteRequested"),
    DELETED("Deleted"),
    IMPORTED("Imported"),
    RETRY_REQUESTED("RetryRequested");

    private final String wire;

    ResourceEventType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** The type a wire name spells, or null. */
    public static ResourceEventType of(String wire) {
        if (wire == null) {
            return null;
        }
        for (ResourceEventType t : values()) {
            if (t.wire.equals(wire)) {
                return t;
            }
        }
        return null;
    }
}
