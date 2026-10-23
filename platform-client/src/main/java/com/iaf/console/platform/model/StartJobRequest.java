package com.iaf.console.platform.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What POST /api/v1/jobs takes: the workflow, an optional pinned version and the input. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartJobRequest(String workflow, String version, Map<String, Object> input, String channel, String traceId, String changeId) {

    public static StartJobRequest of(String workflow, Map<String, Object> input) {
        return new StartJobRequest(workflow, null, input, null, null, null);
    }
}
