package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Where a finding's revert job stands; absent when none was started. */
public enum RevertStatus {
    RUNNING("running"), SUCCEEDED("succeeded"), FAILED("failed");

    private final String id;

    RevertStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static RevertStatus of(String id) {
        for (RevertStatus s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }
}
