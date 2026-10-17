package com.iaf.platform.resources.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** One entry of the resource registry: what a job created, who owns it and what it was built from. */
public record Resource(
        String id,
        String type,
        String name,
        String status,
        JsonNode attributes,
        String owner,
        String visibility,
        Origin createdBy,
        List<String> builtFrom,
        Instant createdAt,
        Instant updatedAt) {

    public record Origin(String jobId, String workflow, String version) {
    }

    public Resource withStatus(String s, Instant at) {
        return new Resource(id, type, name, s, attributes, owner, visibility, createdBy, builtFrom, createdAt, at);
    }

    public Resource withAttributes(JsonNode a, String s, Instant at) {
        return new Resource(id, type, name, s == null ? status : s, a, owner, visibility, createdBy, builtFrom, createdAt, at);
    }
}
