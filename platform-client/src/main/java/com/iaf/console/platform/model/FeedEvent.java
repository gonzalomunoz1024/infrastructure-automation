package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** The P-01 envelope of the platform's event feed; key, not id, is the event's identity. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FeedEvent(
        String specVersion,
        int dataVersion,
        String id,
        String key,
        long seq,
        String type,
        Instant at,
        String aggregate,
        String aggregateId,
        String actor,
        Person onBehalfOf,
        JobContext context,
        String traceparent,
        JsonNode data) {

    public boolean is(String typePrefix) {
        return type != null && type.startsWith(typePrefix);
    }
}
