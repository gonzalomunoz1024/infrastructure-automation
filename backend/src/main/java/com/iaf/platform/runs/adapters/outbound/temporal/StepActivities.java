package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
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

    /**
     * Where a provider's action runs (a local activity): the team worker's route when the
     * provider is an sdk provider, null when the platform calls it ({@link #callProvider}, which
     * also classifies an unknown provider). An sdk provider without the action fails the step.
     */
    WorkerRoute providerRoute(JobRef job, String stepId, String provider, String action);

    /** The {@code traceparent} a step's call on a team's worker carries (a local activity): the step's span ({@code StepTraces}). */
    String traceparent(JobRef job, String stepId);

    /** The telemetry sample of one call a team's worker answered (a local activity); a failure to record it is logged, never the step's. */
    void recordCall(CallSample sample);

    // ---------------------------------------------------------------- records and ids

    /**
     * The child job of a {@code run} step, checked as a start through its own route is (the
     * schema's defaults, the schema, RBAC, the policy): the published child workflow is resolved
     * in {@code version} (a range), or in the current major of the workflow when none is given,
     * and the job built with its id minted here. Nothing is stored or published: the child
     * workflow records itself ({@link #started}) once it runs. Returns the job with its
     * definition snapshot: the workflow starts it as a child workflow.
     */
    Job childJob(JobRef parent, String stepId, String workflow, String version, JsonNode input);

    /**
     * The child job of one batch row (approved, previewed and change-managed once for the
     * batch), built like {@link #childJob} and recorded by its own workflow the same way.
     * Neither argument nor result carries the definition: the workflow attaches the batch's own
     * snapshot, so a 500-row batch does not ship it through 500 activities.
     */
    Job rowJob(JobRef batch, BatchRow row);

    /**
     * {@code job.started} of a child job or a batch row, from the child workflow's own first
     * activity: the job is stored with its definition snapshot and the event published once the
     * child actually runs, so a child whose start never happened leaves no queued job behind. A
     * job already stored (a retry of this activity) is left as it is.
     */
    void started(Job job);

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

    /**
     * {@code job.attention-resolved}, with the edited input of an edit-input decision (null for
     * the others; an activity scheduled before the input was added runs with null too).
     */
    void attentionResolved(String jobId, String stepId, String action, String by, JsonNode input);

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
