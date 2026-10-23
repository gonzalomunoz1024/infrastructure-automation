package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A human sign-off a job waits for, or got. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Approval(
        String id,
        String jobId,
        String workflow,
        String step,
        String group,
        String message,
        Instant requestedAt,
        String timeout,
        String status,
        String decidedBy,
        Instant decidedAt,
        String comment,
        Preview preview,
        String escalatedTo) {
}
