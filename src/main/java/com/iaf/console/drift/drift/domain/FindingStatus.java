package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** A finding is open until a decision or a later read closes it. */
public enum FindingStatus {
    OPEN("open"), CLOSED("closed");

    private final String id;

    FindingStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static FindingStatus of(String id) {
        for (FindingStatus s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }
}
