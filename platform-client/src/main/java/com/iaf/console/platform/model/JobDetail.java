package com.iaf.console.platform.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A job with its steps, approvals, preview, output and children, as GET /api/v1/jobs/{id} answers it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobDetail(
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
        JobSummary.Progress progress,
        Integer row,
        String errorClass,
        String failedStep,
        String actor,
        Person onBehalfOf,
        JobContext context,
        Map<String, Object> input,
        Map<String, Object> output,
        List<JobStep> steps,
        List<Approval> approvals,
        Preview preview,
        ChangeTicket change,
        List<JobSummary> children,
        Batch batch,
        Attention attention,
        String traceId,
        Map<String, String> links) {

    /** The summary part of this job. */
    public JobSummary summary() {
        return new JobSummary(id, workflow, title, version, status, requester, channel, startedAt, endedAt, durationMs, parentId, kind, progress, row,
                errorClass, failedStep, actor, onBehalfOf, context);
    }

    public boolean finished() {
        return status != null && status.terminal();
    }
}
