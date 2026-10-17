package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.StepExecutor.ApprovalRequest;
import com.iaf.platform.runs.domain.StepExecutor.RegisterRequest;
import com.iaf.platform.runs.domain.StepExecutor.UseRequest;
import com.iaf.platform.workflows.domain.Step;

import io.temporal.activity.ActivityInterface;

/**
 * Everything the interpreter does to the world, as activities: the calls of a step (through the
 * same ports the in-memory engine uses), the records it creates (approvals, child jobs, their
 * ids) and every {@code job.*} / {@code batch.*} event it publishes. Waiting, sleeping, deciding
 * and walking stay inside the workflow. A step failure crosses the boundary as an
 * {@code ApplicationFailure} of type {@link StepFailureData#TYPE}.
 */
@ActivityInterface
public interface StepActivities {

    // ---------------------------------------------------------------- the calls of a step

    /** {@code call: provider}. */
    JsonNode callProvider(StepCall call, String provider, String action, JsonNode input);

    /** {@code call: http | ansible | terraform | idp | activity}. */
    JsonNode runAutomation(StepCall call, JsonNode with);

    /** {@code notify}. */
    JsonNode notify(StepCall call, String channel, String message);

    /** {@code script}. */
    JsonNode script(StepCall call, String language, String code, JsonNode input);

    /** {@code use}: one lookup; the workflow polls with timers when the step waits until the resource is ready. */
    JsonNode useResource(StepCall call, UseRequest request);

    /** {@code register}. */
    JsonNode registerResource(StepCall call, RegisterRequest request);

    // ---------------------------------------------------------------- records and ids

    /**
     * The child job of a {@code run} step: resolves the published child workflow in {@code range},
     * builds the job (its id minted here), stores it in the projection and publishes
     * {@code job.started}. Returns the job with its definition snapshot: the workflow starts it as
     * a child workflow.
     */
    Job childJob(JobRef parent, String stepId, String workflow, String range, JsonNode input);

    /**
     * The child job of one batch row (approved, previewed and change-managed once for the
     * batch), stored with the batch's definition snapshot and started like {@link #childJob}.
     * Neither argument nor result carries the definition: the workflow attaches the batch's own
     * snapshot, so a 500-row batch does not ship it through 500 activities.
     */
    Job rowJob(JobRef batch, BatchRow row);

    /** Create the approval of a step and publish {@code job.approval-requested}; returns its id. */
    String requestApproval(JobRef job, Step step, ApprovalRequest request, Preview preview);

    /** The one approval of a whole batch, published; returns the record. */
    Approval batchApproval(JobRef batch, String group, String message, String timeout, Preview preview);

    /** {@code job.approval-decided} the engine decides itself (a batch approval that expired). */
    void approvalDecided(Approval approval, ApprovalDecision decision);

    /** {@code job.approval-escalated}. */
    void escalateApproval(String jobId, String stepId, String approvalId, String to);

    // ---------------------------------------------------------------- events

    /** {@code job.attention}. */
    void publishAttention(String jobId, Attention attention);

    /** {@code job.attention-resolved}. */
    void attentionResolved(String jobId, String stepId, String action, String by);

    /** {@code job.step}. */
    void publishStep(String jobId, StepEventData event);

    /** {@code job.log}. */
    void log(String jobId, String message);

    /** {@code batch.wave}. */
    void wave(String jobId, Wave wave, String reason, List<Integer> rows, Instant at);

    /** {@code job.finished}, then the job's change record is closed. */
    void finished(JobRef job, Outcome outcome, Instant at);

    /** {@code job.cancelled}, then the job's change record is closed. */
    void cancelled(JobRef job, String by, Instant at);
}
