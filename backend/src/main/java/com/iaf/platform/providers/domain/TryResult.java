package com.iaf.platform.providers.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.schema.Schemas.Mismatch;

/**
 * The outcome of "Try it" in the wizard: the real response (the candidate {@code sample} the
 * wizard records), how the response differs from the declared output schema, and, for a delete
 * action with a {@code verify} block, whether the verify action proved the effect.
 */
public record TryResult(
        boolean ok,
        int status,
        long durationMs,
        JsonNode response,
        List<Mismatch> mismatches,
        Boolean verified) {
}
