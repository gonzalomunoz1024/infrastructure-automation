package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A job as GET /api/v1/jobs lists it, with P-04's context, actor and the user it was started for. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobSummary(
        String id,
        String workflow,
        String title,
        String version,
        JobStatus status,
        String requester,
        String channel,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String parentId,
        String kind,
        Progress progress,
        Integer row,
        String errorClass,
        String failedStep,
        String actor,
        Person onBehalfOf,
        JobContext context) {

    /** How far a batch is. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Progress(Integer done, Integer total, Integer failed) {
    }
}
