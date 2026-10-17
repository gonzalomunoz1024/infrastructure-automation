package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The life of a job, as docs/api.md spells it. */
public enum JobStatus {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting-approval"),
    NEEDS_ATTENTION("needs-attention"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ROLLED_BACK("rolled-back");

    private final String key;

    JobStatus(String key) {
        this.key = key;
    }

    @JsonValue
    public String json() {
        return key;
    }

    @JsonCreator
    public static JobStatus of(String key) {
        for (JobStatus s : values()) {
            if (s.key.equals(key) || s.name().equalsIgnoreCase(key)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown job status '" + key + "'");
    }

    /** The job ended: nothing more will happen to it. */
    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }

    /** The job is alive: queued, running or waiting for a person. */
    public boolean active() {
        return !terminal();
    }
}
