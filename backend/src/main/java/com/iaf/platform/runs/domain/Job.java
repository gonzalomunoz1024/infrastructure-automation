package com.iaf.platform.runs.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import lombok.Builder;
import lombok.With;

/**
 * One run of one workflow version. The first block of fields is what docs/api.md shows
 * (JobSummary + JobDetail); the rest is what the engine needs: the pinned definition snapshot (a
 * new published version never changes a running job), the requester's identity, and the resume
 * point of a rerun or a seeded live job.
 */
@Builder(toBuilder = true)
@With
public record Job(
        String id,
        String workflow,
        String title,
        String version,
        JobStatus status,
        Identity requester,
        String channel,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String parentId,
        Kind kind,
        Progress progress,
        JsonNode input,
        JsonNode output,
        List<JobStep> steps,
        List<Approval> approvals,
        Preview preview,
        ChangeTicket change,
        Batch batch,
        Attention attention,
        String traceId,
        // engine-side
        WorkflowDefinition definition,
        String environment,
        String errorClass,
        String failedStep,
        PolicyDecision policy,
        Resume resume,
        Integer row,
        boolean preApproved) {

    public enum Kind {
        SINGLE, BATCH;

        @JsonValue
        public String json() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static Kind of(String key) {
            return valueOf(key.toUpperCase());
        }
    }

    /** Batch progress: rows done (succeeded or failed) out of the total, and how many failed. */
    public record Progress(int done, int total, int failed) {
    }

    /**
     * Where a job picks up instead of starting from the first step: a rerun copies the recorded
     * steps before {@code fromStep}; a seeded live job resumes at the step it is waiting on.
     */
    public record Resume(String fromStep, List<JobStep> seed) {
    }

    public String requesterId() {
        return requester == null ? null : requester.id();
    }

    public boolean isBatch() {
        return kind == Kind.BATCH;
    }

    public List<JobStep> steps() {
        return steps == null ? List.of() : steps;
    }

    public List<Approval> approvals() {
        return approvals == null ? List.of() : approvals;
    }

    public JobStep step(String id) {
        return steps().stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** The pending approval of a step, when there is one. */
    public Approval pendingApproval(String step) {
        return approvals().stream().filter(a -> a.pending() && (step == null || step.equals(a.step()))).findFirst().orElse(null);
    }
}
