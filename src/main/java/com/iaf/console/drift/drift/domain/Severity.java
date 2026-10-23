package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How much a finding matters: a missing resource or production is critical, a revertable field a warning, the rest information. */
public enum Severity {
    INFO("info"), WARNING("warning"), CRITICAL("critical");

    private final String id;

    Severity(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static Severity of(String id) {
        for (Severity s : values()) {
            if (s.id.equals(id)) {
                return s;
            }
        }
        return null;
    }

    public boolean atLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }
}
