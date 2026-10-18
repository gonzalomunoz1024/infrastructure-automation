package com.iaf.platform.runs.ports.inbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import reactor.core.publisher.Mono;

/**
 * Write side of the runs slice. The catalog gateway, the MCP server, the triggers and the UI
 * all start and steer jobs through this port.
 */
public interface RunsCommands {

    /**
     * @param version  the version to run; null means "whatever the release router says"
     * @param channel  catalog, rest, mcp, trigger or batch
     * @param parentId the parent job for child workflows and batch rows
     * @param preview  the preview shown to the requester, stored on the job (may be null)
     */
    record StartJob(String workflow, String version, JsonNode input, Identity requester, String channel,
            String parentId, String traceId, Preview preview, String changeId) {
    }

    /** {@code traceId}: the trace the batch and its rows run in (the request's); null starts a new one. */
    record StartBatch(String workflow, String version, List<JsonNode> items, JsonNode defaults, RolloutPolicy rollout,
            Identity requester, String channel, Preview preview, String traceId) {

        public StartBatch(String workflow, String version, List<JsonNode> items, JsonNode defaults, RolloutPolicy rollout,
                Identity requester, String channel, Preview preview) {
            this(workflow, version, items, defaults, rollout, requester, channel, preview, null);
        }
    }

    Mono<CommandAccepted> startJob(StartJob command);

    /** 400 (IllegalArgumentException) with every invalid row before anything starts. */
    Mono<CommandAccepted> startBatch(StartBatch command);

    Mono<CommandAccepted> cancel(String jobId);

    /** needs-attention: run the failed step again, optionally with edited input. */
    Mono<CommandAccepted> retry(String jobId, String step, JsonNode input);

    Mono<CommandAccepted> skip(String jobId, String step);

    /** needs-attention: stop and run compensation. */
    Mono<CommandAccepted> abort(String jobId);

    /** A new job that reuses this job's outputs up to {@code fromStep} and runs from there. */
    Mono<CommandAccepted> rerun(String jobId, String fromStep, JsonNode input);

    Mono<CommandAccepted> retryFailedRows(String batchJobId);

    Mono<CommandAccepted> approve(String approvalId, String comment);

    Mono<CommandAccepted> reject(String approvalId, String comment);

    /** Walk a definition against recorded samples without starting a job. */
    Mono<DryRunResult> dryRun(WorkflowDefinition definition, JsonNode input, Identity who);

    /** The blast radius of a run: plan operations, affected resources, cost, policy. */
    Mono<Preview> preview(WorkflowDefinition definition, JsonNode input, Identity who);

    /** A job started by a trigger. */
    record TriggerStarted(String workflow, String jobId) {
    }

    /** A matching workflow that could not start (the caller may not invoke it, or the trigger's input did not validate). */
    record TriggerSkipped(String workflow, String reason) {
    }

    /** What {@code POST /triggers/alerts} and {@code /triggers/events} answer. */
    record TriggerResult(List<TriggerStarted> started, List<TriggerSkipped> skipped) {
    }

    /**
     * {@code POST /triggers/alerts}: start every published workflow whose alert trigger matches
     * the payload ({@code match.source} equal, every {@code match.labels} entry equal), with the
     * trigger's {@code input} resolved against {@code $alert}.
     */
    Mono<TriggerResult> triggerAlert(JsonNode alert);

    /** {@code POST /triggers/events}: start every published workflow whose event trigger topic matches, with {@code $event} bound to the payload. */
    Mono<TriggerResult> triggerEvent(String topic, JsonNode payload);
}
