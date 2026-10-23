package com.iaf.console.drift.monitoring.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Which side of its threshold makes an alarm fire: a value strictly above it, or strictly below it. */
public enum Comparison {
    ABOVE("above"),
    BELOW("below");

    private final String id;

    Comparison(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** Whether the value is on the firing side of the threshold. */
    public boolean crossed(double value, double threshold) {
        return this == ABOVE ? value > threshold : value < threshold;
    }

    /** The comparison a word names; null for a word it does not know. */
    @JsonCreator
    public static Comparison of(String value) {
        for (Comparison c : values()) {
            if (c.id.equals(value)) {
                return c;
            }
        }
        return null;
    }
}
