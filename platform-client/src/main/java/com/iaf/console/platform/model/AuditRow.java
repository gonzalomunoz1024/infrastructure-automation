package com.iaf.console.platform.model;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One evidence record as GET /api/v1/audit lists them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditRow(
        String jobId,
        String workflow,
        String requester,
        String channel,
        Instant at,
        int entries,
        String status,
        String accountId,
        String wfrn) {
}
