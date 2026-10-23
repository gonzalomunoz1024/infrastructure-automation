package com.iaf.console.fake.runs.domain;

/** The states of a job, the platform's, terminal ones last. */
public enum JobStatus {

    QUEUED("queued"),
    RUNNING("running"),
    WAITING_APPROVAL("waiting-approval"),
    NEEDS_ATTENTION("needs-attention"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    ROLLED_BACK("rolled-back");

    private final String id;

    JobStatus(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == ROLLED_BACK;
    }

    /** The status an id names, null for an unknown one. */
    public static JobStatus of(String id) {
        for (JobStatus status : values()) {
            if (status.id.equals(id)) {
                return status;
            }
        }
        return null;
    }
}
