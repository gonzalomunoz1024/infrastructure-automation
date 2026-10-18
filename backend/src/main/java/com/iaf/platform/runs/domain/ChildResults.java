package com.iaf.platform.runs.domain;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a run step returns when its child job succeeded:
 * {@code { jobId, status, outputs, resources: [{ id, type }] }}, where {@code resources} are the
 * resources the child's register steps recorded. Every engine builds the result here, so a
 * parent finds the resource an {@code orCreateWith} child built the same way everywhere.
 */
public final class ChildResults {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ChildResults() {
    }

    /** @param child the child's context after its run, null when the engine has none */
    public static ObjectNode of(String jobId, Outcome outcome, JobContext child) {
        ObjectNode out = F.objectNode();
        out.put("jobId", jobId);
        out.put("status", outcome.status().json());
        out.set("outputs", outcome.output() == null ? F.objectNode() : outcome.output());
        ArrayNode resources = out.putArray("resources");
        if (child != null) {
            child.registered().forEach(r -> resources.addObject().put("id", r.id()).put("type", r.type()));
        }
        return out;
    }

    /** The id of the last resource of {@code type} the child registered, from a run step's output. */
    public static Optional<String> registered(JsonNode result, String type) {
        String id = null;
        for (JsonNode r : result == null ? F.arrayNode() : result.path("resources")) {
            if (type.equals(r.path("type").asText()) && !r.path("id").asText("").isBlank()) {
                id = r.path("id").asText();
            }
        }
        return Optional.ofNullable(id);
    }
}
