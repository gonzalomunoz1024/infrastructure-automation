package com.iaf.platform.releases.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** Where a release stands: routing a share of jobs to the candidate, paused, or finished either way. */
public enum ReleaseStatus {
    CANARY, PAUSED, PROMOTED, ROLLED_BACK;

    @JsonValue
    public String json() {
        return name().toLowerCase().replace('_', '-');
    }

    /** Still owns traffic decisions: a canary or a paused one. */
    public boolean active() {
        return this == CANARY || this == PAUSED;
    }
}
