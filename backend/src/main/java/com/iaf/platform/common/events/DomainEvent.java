package com.iaf.platform.common.events;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Something that happened, named in the past tense ({@code job.started}, {@code workflow.published}).
 * Command handlers publish them; projections and the audit log listen.
 */
public record DomainEvent(String id, Instant at, String type, String aggregate, String aggregateId, String actor, JsonNode data) {

    public boolean is(String prefix) {
        return type.startsWith(prefix);
    }
}
