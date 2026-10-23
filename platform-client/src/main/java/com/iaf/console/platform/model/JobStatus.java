package com.iaf.console.platform.model;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonValue;

/** The states of a job, terminal ones last. */
public enum JobStatus {

    QUEUED("queued"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting-approval"),
    NEEDS_ATTENTION("needs-attention"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ROLLED_BACK("rolled-back"),
    /** A status this client does not know yet. */
    @JsonEnumDefaultValue
    UNKNOWN("unknown");

    private final String id;

    JobStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }

    public static JobStatus of(String id) {
        for (JobStatus s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return UNKNOWN;
    }
}
