package com.iaf.platform.providers.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.schema.Schemas.Mismatch;

/**
 * The outcome of "Try it" in the wizard: the real response, how it differs from the declared
 * output schema, and, for a delete action with a {@code verify} block, whether the verify action
 * proved the effect. A call that went through also comes back as {@code yaml}: the provider draft
 * with the response saved as the action's {@code sample} and {@code recorded: { environment, at }}.
 */
public record TryResult(
        boolean ok,
        int status,
        long durationMs,
        JsonNode response,
        List<Mismatch> mismatches,
        Boolean verified,
        String environment,
        Instant at,
        String yaml) {

    public TryResult(boolean ok, int status, long durationMs, JsonNode response, List<Mismatch> mismatches, Boolean verified) {
        this(ok, status, durationMs, response, mismatches, verified, null, null, null);
    }
}
