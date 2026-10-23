package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** A published workflow as the console draws its buttons from it, with P-08's subject, effect and mutable fields. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogEntry(
        String workflow,
        String title,
        String noun,
        String verb,
        String persona,
        String method,
        String path,
        String batchPath,
        String previewPath,
        JsonNode input,
        String mcpTool,
        String owner,
        String version,
        String description,
        BatchSpec batch,
        Deprecation deprecated,
        String subjectInput,
        Boolean destructive,
        Boolean approval,
        List<String> mutableFields,
        Boolean bulk,
        String effect) {

    /** The batch limits of a workflow. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BatchSpec(Integer max, RolloutPolicy rollout) {
    }

    /** A deprecated workflow: since when, until when, and what replaces it. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Deprecation(String since, String sunset, String replacement, String message) {
    }

    public boolean hasPreview() {
        return previewPath != null && !previewPath.isBlank();
    }

    public boolean isDestructive() {
        return Boolean.TRUE.equals(destructive);
    }

    public boolean needsApproval() {
        return Boolean.TRUE.equals(approval);
    }
}
