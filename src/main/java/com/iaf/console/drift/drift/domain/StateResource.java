package com.iaf.console.drift.drift.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A resource as the state service answers it ({@code ConsoleResource}), the part the drift service reads. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StateResource(
        String wfrn,
        String accountId,
        String region,
        String type,
        String service,
        String name,
        String state,
        long version,
        String registryId,
        String providerId,
        ObjectNode attributes,
        ObjectNode changes,
        ObjectNode observed,
        String observedStatus,
        Instant observedAt,
        OpenDrift drift,
        Map<String, String> tags,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String source) {

    /** The open finding the state service records on a resource. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenDrift(String findingId, Instant since, String severity) {
    }

    public boolean deleted() {
        return "deleted".equals(state);
    }

    /** The environment the resource lives in, from its attributes or its tags. */
    public String environment() {
        if (attributes != null && attributes.hasNonNull("environment")) {
            return attributes.get("environment").asText();
        }
        if (tags != null) {
            if (tags.containsKey("environment")) {
                return tags.get("environment");
            }
            if (tags.containsKey("env")) {
                return tags.get("env");
            }
        }
        return null;
    }
}
