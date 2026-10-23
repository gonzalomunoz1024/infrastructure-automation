package com.iaf.console.drift.drift.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** What a person says when they revert, accept or ignore a finding: why, and who (when the BFF relays it). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Decision(String reason, String actor) {

    public static final Decision NONE = new Decision(null, null);

    public String actorOr(String fallback) {
        return actor == null || actor.isBlank() ? fallback : actor;
    }
}
