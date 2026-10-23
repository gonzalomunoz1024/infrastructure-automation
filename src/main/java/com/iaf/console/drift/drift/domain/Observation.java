package com.iaf.console.drift.drift.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** What one read of a provider said about a resource: found with its mapped fields, not found, or an error. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Observation(String status, JsonNode observed, Instant at, String provider, String action, String error) {

    public static final String FOUND = "found";
    public static final String NOT_FOUND = "not_found";
    public static final String ERROR = "error";

    public static Observation found(JsonNode observed, Instant at, String provider, String action) {
        return new Observation(FOUND, observed == null ? Json.object() : observed, at, provider, action, null);
    }

    public static Observation notFound(Instant at, String provider, String action) {
        return new Observation(NOT_FOUND, null, at, provider, action, null);
    }

    public static Observation error(String message, Instant at, String provider, String action) {
        return new Observation(ERROR, null, at, provider, action, message);
    }

    public boolean found() {
        return FOUND.equals(status);
    }

    public boolean notFound() {
        return NOT_FOUND.equals(status);
    }

    public boolean failed() {
        return ERROR.equals(status);
    }

    /** A digest of status and values, so an unchanged observation is not sent to the state service again. */
    public String digest() {
        return Json.sha256(status + ":" + (observed == null ? "" : DriftRules.normalize(observed).toString()));
    }
}
