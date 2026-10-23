package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One step of a job: its state, timing, input, output and error. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobStep(
        String id,
        String name,
        String type,
        String status,
        Integer attempt,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        Map<String, Object> input,
        Map<String, Object> output,
        StepError error,
        Integer items) {
}
