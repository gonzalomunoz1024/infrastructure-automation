package com.iaf.console.platform.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** An error body of the platform: error, correlation id, problems or batch rows, retry-after and detail. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlatformError(
        String error,
        String correlationId,
        JsonNode detail,
        Integer retryAfter,
        List<Problem> problems,
        List<RowProblems> rows) {

    /** The problems of one batch row. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RowProblems(int index, List<Problem> problems) {
    }
}
