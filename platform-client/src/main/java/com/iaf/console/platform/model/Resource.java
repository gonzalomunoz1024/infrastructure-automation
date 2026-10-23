package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A registered resource, with P-05's wfrn, account, region and provider id. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Resource(
        String id,
        String type,
        String name,
        String status,
        Map<String, Object> attributes,
        String owner,
        String visibility,
        ResourceOrigin createdBy,
        List<String> builtFrom,
        Instant createdAt,
        Instant updatedAt,
        String wfrn,
        String accountId,
        String region,
        String providerId,
        String supersedes) {

    /** An attribute as text, null when absent. */
    public String attribute(String name) {
        Object value = attributes == null ? null : attributes.get(name);
        return value == null ? null : value.toString();
    }
}
