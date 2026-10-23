package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** How often a resource is read: hot every 15 minutes, warm on the type's interval, cold daily (the plan's three tiers). */
public enum Tier {
    HOT("hot"), WARM("warm"), COLD("cold");

    private final String id;

    Tier(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    @JsonCreator
    public static Tier of(String id) {
        for (Tier t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
