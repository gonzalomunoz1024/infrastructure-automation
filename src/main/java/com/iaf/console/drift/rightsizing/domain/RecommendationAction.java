package com.iaf.console.drift.rightsizing.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What applying a recommendation does: run the type's delete workflow (an idle resource) or its update workflow with a smaller size. */
public enum RecommendationAction {
    DELETE("delete"),
    RESIZE("resize");

    private final String id;

    RecommendationAction(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** The action a word names; null for a word it does not know. */
    @JsonCreator
    public static RecommendationAction of(String value) {
        for (RecommendationAction a : values()) {
            if (a.id.equals(value)) {
                return a;
            }
        }
        return null;
    }
}
