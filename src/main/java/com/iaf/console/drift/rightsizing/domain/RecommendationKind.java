package com.iaf.console.drift.rightsizing.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What the utilisation says about a resource: {@code idle} (nothing uses it) or {@code oversized} (it is larger than its use). */
public enum RecommendationKind {
    IDLE("idle"),
    OVERSIZED("oversized");

    private final String id;

    RecommendationKind(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** The kind a word names; null for a word it does not know. */
    @JsonCreator
    public static RecommendationKind of(String value) {
        for (RecommendationKind k : values()) {
            if (k.id.equals(value)) {
                return k;
            }
        }
        return null;
    }
}
