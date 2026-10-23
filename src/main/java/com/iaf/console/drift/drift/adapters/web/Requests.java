package com.iaf.console.drift.drift.adapters.web;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.iaf.console.drift.drift.domain.Decision;

/** The request bodies of the API, as the contract names their fields. */
public final class Requests {

    private Requests() {
    }

    /** {@code Decision}: why, and who when the BFF relays it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecisionRequest(String reason, String actor) {
        Decision decision() {
            return new Decision(reason, actor);
        }
    }

    /** An ignore: a decision and, optionally, until when. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IgnoreRequest(String reason, String actor, Instant until) {
        Decision decision() {
            return new Decision(reason, actor);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiscoverRequest(String type, String region) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImportRequest(String type, String region, String providerId, String name) {
    }

    /** {@code POST /v1/unmanaged:assign}: the object, the account it is handed to, a note, and who when the BFF relays it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AssignRequest(String type, String region, String providerId, String accountId, String note, String actor) {
    }
}
