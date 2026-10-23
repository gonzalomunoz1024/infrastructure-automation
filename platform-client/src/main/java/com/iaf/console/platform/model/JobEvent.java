package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One event of one job, as its SSE stream sends it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobEvent(long seq, Instant at, String jobId, String type, Map<String, Object> data) {
}
