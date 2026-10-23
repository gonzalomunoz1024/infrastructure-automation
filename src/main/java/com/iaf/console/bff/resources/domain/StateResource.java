package com.iaf.console.bff.resources.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** A resource as the state service holds it ({@code ConsoleResource} of its contract): the console's view of one wfrn. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StateResource(
        String wfrn,
        String accountId,
        String region,
        String type,
        String service,
        String name,
        String state,
        Long version,
        String registryId,
        String providerId,
        JsonNode attributes,
        JsonNode observed,
        Instant observedAt,
        String observedStatus,
        Drift drift,
        Map<String, String> tags,
        String requestedBy,
        String lastJobId,
        JsonNode lastError,
        JsonNode changes,
        String source,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Instant tombstoneUntil) {

    public static final String PENDING = "pending";
    public static final String ACTIVE = "active";
    public static final String UPDATING = "updating";
    public static final String DELETING = "deleting";
    public static final String DELETED = "deleted";
    public static final String FAILED = "failed";

    /** An open drift finding on the resource, as the state service records it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Drift(String findingId, Instant since, String severity, JsonNode fields) {
    }

    public StateResource {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public boolean live() {
        return !DELETED.equals(state);
    }

    public boolean is(String expected) {
        return expected != null && expected.equals(state);
    }
}
