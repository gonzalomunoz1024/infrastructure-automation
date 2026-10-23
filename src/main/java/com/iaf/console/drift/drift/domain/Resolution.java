package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a finding closed: the contract's and the state service's four resolutions. */
public enum Resolution {
    REVERTED("reverted"), ACCEPTED("accepted"), IGNORED("ignored"), VANISHED("vanished");

    private final String id;

    Resolution(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static Resolution of(String id) {
        for (Resolution r : values()) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        return null;
    }
}
