package com.iaf.platform.audit.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** What an evidence entry records about a job. Shape as in docs/api.md. */
public enum EvidenceKind {
    REQUESTED, PREVIEW, POLICY, CHANGE, APPROVAL, STEP, CHANGED, CLOSED, DECISION;

    @JsonValue
    public String json() {
        return name().toLowerCase();
    }

    @JsonCreator
    public static EvidenceKind of(String s) {
        return valueOf(s.toUpperCase());
    }
}
