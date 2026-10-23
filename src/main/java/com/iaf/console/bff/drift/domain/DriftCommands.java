package com.iaf.console.bff.drift.domain;

import java.time.Instant;

/**
 * The writes the BFF sends the drift service for a person, with the person already named
 * ({@code actor} is the session's user id, never what the browser said) and the headers each
 * write carries. The account and the region come from the session's scope.
 */
public final class DriftCommands {

    private DriftCommands() {
    }

    /** A decision on a finding or a recommendation: why, who, and until when for an ignore or a dismissal. */
    public record Decision(String reason, String actor, Instant until) {
    }

    /** Taking a discovered object under management. */
    public record Import(String type, String providerId, String name) {
    }

    /** Handing a discovered object to another account. */
    public record Assign(String type, String providerId, String accountId, String note, String actor) {
    }

    /**
     * What a write carries besides its body: the web app's {@code Idempotency-Key} when it sent one,
     * the console request id ({@code X-Console-Request-Id}), and for a revert or an apply the
     * signed {@code X-On-Behalf-Of} assertion for the person (P-02), since those run a workflow for them.
     */
    public record Meta(String idempotencyKey, String requestId, String onBehalfOf) {
    }
}
