package com.iaf.console.platform.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** What POST /api/v1/jobs:batch takes: one input per row, defaults, the rollout and per-row contexts. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record StartBatchRequest(
        String workflow,
        String version,
        List<Map<String, Object>> items,
        Map<String, Object> defaults,
        RolloutPolicy rollout,
        String channel,
        String traceId,
        List<BatchContext> contexts) {
}
