package com.iaf.console.drift.scorecards.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How a resource stands against a rule that applies to it; {@code error} when the engine could not judge it, which counts neither way. */
public enum RuleStatus {
    PASS("pass"), FAIL("fail"), ERROR("error");

    private final String id;

    RuleStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static RuleStatus of(String id) {
        for (RuleStatus s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }
}
