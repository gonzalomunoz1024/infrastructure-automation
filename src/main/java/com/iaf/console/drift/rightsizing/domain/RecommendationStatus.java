package com.iaf.console.drift.rightsizing.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a recommendation stands. {@code open} and {@code dismissed} are current: a resource has at
 * most one current recommendation, which later passes refresh, reopen or supersede. {@code applied}
 * (its job was started) and {@code superseded} (the resource changed size, vanished or is no longer
 * idle or oversized) are final.
 */
public enum RecommendationStatus {
    OPEN("open"),
    APPLIED("applied"),
    DISMISSED("dismissed"),
    SUPERSEDED("superseded");

    private final String id;

    RecommendationStatus(String id) {
        this.id = id;
    }

    @JsonValue
    public String id() {
        return id;
    }

    /** Open or dismissed: the one a pass may still change. */
    public boolean current() {
        return this == OPEN || this == DISMISSED;
    }

    /** The status a word names; null for a word it does not know. */
    @JsonCreator
    public static RecommendationStatus of(String value) {
        for (RecommendationStatus s : values()) {
            if (s.id.equals(value)) {
                return s;
            }
        }
        return null;
    }
}
