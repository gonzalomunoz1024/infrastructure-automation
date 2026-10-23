package com.iaf.console.drift.monitoring.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where an alarm stands after its last evaluation: {@code alarm} when the window's average crossed
 * the threshold, {@code ok} when it did not, {@code insufficient} when the provider gave no points.
 * The rank orders a list with the firing alarms first.
 */
public enum AlarmState {
    ALARM("alarm", 0),
    INSUFFICIENT("insufficient", 1),
    OK("ok", 2);

    private final String id;
    private final int rank;

    AlarmState(String id, int rank) {
        this.id = id;
        this.rank = rank;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** The list order: alarm, insufficient, ok. */
    public int rank() {
        return rank;
    }

    /** The state a word names; null for a word it does not know. */
    @JsonCreator
    public static AlarmState of(String value) {
        for (AlarmState s : values()) {
            if (s.id.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
