package com.iaf.platform.runs.application;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.tracing.TraceIds;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

/** How job records are built: a new run, a child of a run step, a batch row, a rerun. */
public final class JobFactory {

    public static final String DEFAULT_ENVIRONMENT = "dev";

    private JobFactory() {
    }

    public static String environmentOf(JsonNode input) {
        JsonNode e = input == null ? null : input.get("environment");
        return e == null || !e.isTextual() || e.asText().isBlank() ? DEFAULT_ENVIRONMENT : e.asText();
    }

    public static Job newJob(WorkflowDefinition def, String version, JsonNode input, Identity who, String channel, String parentId,
            String traceId, Preview preview, PolicyDecision policy, ChangeTicket change, Instant now) {
        return Job.builder()
                .id(Ids.next("job"))
                .workflow(def.name())
                .title(def.title())
                .version(version == null ? def.version() : version)
                .status(JobStatus.QUEUED)
                .requester(who == null ? Identity.SYSTEM : who)
                .channel(channel == null ? "rest" : channel)
                .startedAt(now)
                .parentId(parentId)
                .kind(Job.Kind.SINGLE)
                .input(input == null ? JsonNodeFactory.instance.objectNode() : input)
                .steps(List.of())
                .approvals(List.of())
                .preview(preview)
                .change(change)
                .traceId(traceId == null ? TraceIds.newTraceId() : traceId)
                .definition(def)
                .environment(environmentOf(input))
                .policy(policy)
                .build();
    }

    /** A batch: the parent of one child per row, released in waves. */
    public static Job newBatch(WorkflowDefinition def, String version, List<JsonNode> rowInputs, JsonNode defaults, RolloutPolicy rollout,
            List<Batch.Wave> waves, Identity who, String channel, Preview preview, PolicyDecision policy, ChangeTicket change, Instant now) {
        return newBatch(def, version, rowInputs, defaults, rollout, waves, who, channel, preview, policy, change, null, now);
    }

    /** A batch in the trace {@code traceId} (the request's); null starts a new trace. Its rows share it. */
    public static Job newBatch(WorkflowDefinition def, String version, List<JsonNode> rowInputs, JsonNode defaults, RolloutPolicy rollout,
            List<Batch.Wave> waves, Identity who, String channel, Preview preview, PolicyDecision policy, ChangeTicket change, String traceId,
            Instant now) {
        List<BatchRow> rows = new java.util.ArrayList<>();
        int next = 0;
        for (int w = 0; w < waves.size(); w++) {
            for (int i = 0; i < waves.get(w).size() && next < rowInputs.size(); i++) {
                rows.add(new BatchRow(next, rowInputs.get(next), null, "pending", null, waves.get(w).index()));
                next++;
            }
        }
        while (next < rowInputs.size()) {
            rows.add(new BatchRow(next, rowInputs.get(next), null, "pending", null, waves.isEmpty() ? 0 : waves.get(waves.size() - 1).index()));
            next++;
        }
        Batch batch = new Batch(rows, waves, rollout, null);
        var input = JsonNodeFactory.instance.objectNode();
        input.put("rows", rows.size());
        if (defaults != null && defaults.isObject() && defaults.size() > 0) {
            input.set("defaults", defaults);
        }
        if (rollout != null) {
            input.set("rollout", JobViews.MAPPER.valueToTree(rollout));
        }
        return Job.builder()
                .id(Ids.next("job"))
                .workflow(def.name())
                .title(def.title())
                .version(version == null ? def.version() : version)
                .status(JobStatus.QUEUED)
                .requester(who == null ? Identity.SYSTEM : who)
                .channel(channel == null ? "batch" : channel)
                .startedAt(now)
                .kind(Job.Kind.BATCH)
                .progress(new Job.Progress(0, rows.size(), 0))
                .input(input)
                .steps(List.of())
                .approvals(List.of())
                .preview(preview)
                .change(change)
                .batch(batch)
                .traceId(traceId == null ? TraceIds.newTraceId() : traceId)
                .definition(def)
                .environment(rowInputs.isEmpty() ? DEFAULT_ENVIRONMENT : environmentOf(rowInputs.get(0)))
                .policy(policy)
                .build();
    }

    /** The child a {@code run} step starts: same requester, channel and trace as the parent. */
    public static Job child(Job parent, WorkflowDefinition def, JsonNode input, Instant now) {
        return Job.builder()
                .id(Ids.next("job"))
                .workflow(def.name())
                .title(def.title())
                .version(def.version())
                .status(JobStatus.QUEUED)
                .requester(parent.requester())
                .channel(parent.channel())
                .startedAt(now)
                .parentId(parent.id())
                .kind(Job.Kind.SINGLE)
                .input(input == null ? JsonNodeFactory.instance.objectNode() : input)
                .steps(List.of())
                .approvals(List.of())
                .traceId(parent.traceId())
                .definition(def)
                .environment(input != null && input.hasNonNull("environment") ? environmentOf(input) : parent.environment())
                // a batch row is approved once for the whole batch, but the workflow its run step
                // starts is another workflow, with approvers of its own: they are asked
                .preApproved(false)
                .build();
    }

    /** One row of a batch: approved, previewed and change-managed once for the whole batch. */
    public static Job row(Job batch, BatchRow row, Instant now) {
        WorkflowDefinition def = batch.definition();
        return Job.builder()
                .id(Ids.next("job"))
                .workflow(batch.workflow())
                .title(def == null ? batch.title() : def.title())
                .version(batch.version())
                .status(JobStatus.QUEUED)
                .requester(batch.requester())
                .channel("batch")
                .startedAt(now)
                .parentId(batch.id())
                .kind(Job.Kind.SINGLE)
                .input(row.input() == null ? JsonNodeFactory.instance.objectNode() : row.input())
                .steps(List.of())
                .approvals(List.of())
                .traceId(batch.traceId())
                .definition(def)
                .environment(environmentOf(row.input()))
                .policy(batch.policy())
                .row(row.index())
                .preApproved(true)
                .build();
    }

    /**
     * A new job that replays {@code source}'s steps before {@code fromStep} and runs from there,
     * with {@code preview}: the source's own while the input is the same, else one computed for
     * the new input (or none).
     */
    public static Job rerun(Job source, String fromStep, JsonNode input, Preview preview, Identity who, String channel, ChangeTicket change, Instant now) {
        return Job.builder()
                .id(Ids.next("job"))
                .workflow(source.workflow())
                .title(source.title())
                .version(source.version())
                .status(JobStatus.QUEUED)
                .requester(who == null ? source.requester() : who)
                .channel(channel == null ? "rest" : channel)
                .startedAt(now)
                .kind(Job.Kind.SINGLE)
                .input(input == null ? source.input() : input)
                .steps(List.of())
                .approvals(List.of())
                .preview(preview)
                .change(change)
                .traceId(TraceIds.newTraceId())
                .definition(source.definition())
                .environment(environmentOf(input == null ? source.input() : input))
                .policy(source.policy())
                .resume(new Job.Resume(fromStep, source.steps()))
                .build();
    }
}
