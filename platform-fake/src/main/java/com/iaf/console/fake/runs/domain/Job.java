package com.iaf.console.fake.runs.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A job of the fake platform: its summary fields, input and output, steps, approvals, attention,
 * batch state and its own event list. Mutated by the interpreter's thread and read by request
 * threads, so every access goes through a synchronized method.
 */
public final class Job {

    /** What a person decided about a paused step or an approval. */
    public record Decision(String action, String by, JsonNode input, String comment) {
    }

    private final String id;
    private final String workflow;
    private final String title;
    private final String version;
    private final String requester;
    private final String channel;
    private final String actor;
    private final ObjectNode onBehalfOf;
    private final JobContext context;
    private final String traceId;
    private final String kind;
    private final String parentId;
    private final Integer row;
    private final String environment;
    private final String ownerTeam;
    private final List<String> requesterTeams;
    private final List<String> requesterRoles;
    private final boolean requesterAdmin;
    private final String rerunOf;
    private final String resumedFrom;
    private final String changeId;
    private JobStatus status = JobStatus.QUEUED;
    private Instant startedAt;
    private Instant endedAt;
    private ObjectNode input;
    private ObjectNode output;
    private JsonNode preview;
    private JsonNode policy;
    private final Map<String, StepRecord> steps = new LinkedHashMap<>();
    private final List<ApprovalRecord> approvals = new ArrayList<>();
    private final List<JobEventRecord> events = new ArrayList<>();
    private AttentionRecord attention;
    private BatchState batch;
    private String errorClass;
    private String failedStep;
    private long updatedNs;
    private long eventSeq;
    private boolean wfrnBound;
    private CompletableFuture<Decision> pending;
    private Thread runner;
    private boolean cancelRequested;

    /** What a job is created from. */
    public record Spec(String id, String workflow, String title, String version, String requester, String channel, String actor,
            ObjectNode onBehalfOf, JobContext context, String traceId, String kind, String parentId, Integer row, String environment,
            String ownerTeam, List<String> requesterTeams, List<String> requesterRoles, boolean requesterAdmin, String rerunOf,
            String resumedFrom, String changeId) {
    }

    public Job(Spec spec, ObjectNode input, Instant startedAt, long updatedNs) {
        this.id = spec.id();
        this.workflow = spec.workflow();
        this.title = spec.title();
        this.version = spec.version();
        this.requester = spec.requester();
        this.channel = spec.channel();
        this.actor = spec.actor();
        this.onBehalfOf = spec.onBehalfOf();
        this.context = spec.context();
        this.traceId = spec.traceId();
        this.kind = spec.kind() == null ? "single" : spec.kind();
        this.parentId = spec.parentId();
        this.row = spec.row();
        this.environment = spec.environment();
        this.ownerTeam = spec.ownerTeam();
        this.requesterTeams = spec.requesterTeams() == null ? List.of() : List.copyOf(spec.requesterTeams());
        this.requesterRoles = spec.requesterRoles() == null ? List.of() : List.copyOf(spec.requesterRoles());
        this.requesterAdmin = spec.requesterAdmin();
        this.rerunOf = spec.rerunOf();
        this.resumedFrom = spec.resumedFrom();
        this.changeId = spec.changeId();
        this.input = input;
        this.startedAt = startedAt;
        this.updatedNs = updatedNs;
    }

    public String id() {
        return id;
    }

    public String workflow() {
        return workflow;
    }

    public String title() {
        return title;
    }

    public String version() {
        return version;
    }

    public String requester() {
        return requester;
    }

    public String channel() {
        return channel;
    }

    public String actor() {
        return actor;
    }

    public ObjectNode onBehalfOf() {
        return onBehalfOf;
    }

    public JobContext context() {
        return context;
    }

    public String accountId() {
        return context == null ? null : context.accountId();
    }

    public String traceId() {
        return traceId;
    }

    public String kind() {
        return kind;
    }

    public boolean isBatch() {
        return "batch".equals(kind);
    }

    public String parentId() {
        return parentId;
    }

    public Integer row() {
        return row;
    }

    public String environment() {
        return environment;
    }

    public String ownerTeam() {
        return ownerTeam;
    }

    public List<String> requesterTeams() {
        return requesterTeams;
    }

    public List<String> requesterRoles() {
        return requesterRoles;
    }

    public boolean requesterAdmin() {
        return requesterAdmin;
    }

    public String rerunOf() {
        return rerunOf;
    }

    public String resumedFrom() {
        return resumedFrom;
    }

    public String changeId() {
        return changeId;
    }

    public synchronized JobStatus status() {
        return status;
    }

    public synchronized Instant startedAt() {
        return startedAt;
    }

    public synchronized Instant endedAt() {
        return endedAt;
    }

    public synchronized ObjectNode input() {
        return input;
    }

    public synchronized ObjectNode output() {
        return output;
    }

    public synchronized JsonNode preview() {
        return preview;
    }

    public synchronized JsonNode policy() {
        return policy;
    }

    public synchronized long updatedNs() {
        return updatedNs;
    }

    public synchronized String errorClass() {
        return errorClass;
    }

    public synchronized String failedStep() {
        return failedStep;
    }

    public synchronized AttentionRecord attention() {
        return attention;
    }

    public synchronized BatchState batch() {
        return batch;
    }

    public synchronized boolean wfrnBound() {
        return wfrnBound;
    }

    public synchronized void bindWfrn() {
        this.wfrnBound = true;
    }

    public synchronized List<StepRecord> steps() {
        return List.copyOf(steps.values());
    }

    public synchronized StepRecord step(String stepId) {
        return steps.get(stepId);
    }

    public synchronized StepRecord stepByIdOrName(String key) {
        StepRecord byId = steps.get(key);
        if (byId != null) {
            return byId;
        }
        return steps.values().stream().filter(s -> key.equals(s.name())).findFirst().orElse(null);
    }

    public synchronized StepRecord addStep(StepRecord step) {
        steps.put(step.id(), step);
        return step;
    }

    public synchronized List<ApprovalRecord> approvals() {
        return List.copyOf(approvals);
    }

    public synchronized void addApproval(ApprovalRecord approval) {
        approvals.add(approval);
    }

    public synchronized List<JobEventRecord> events() {
        return List.copyOf(events);
    }

    public synchronized List<JobEventRecord> eventsAfter(long seq) {
        return events.stream().filter(e -> e.seq() > seq).toList();
    }

    /** Numbers and keeps one event of this job; the caller publishes it to the feed. */
    public synchronized JobEventRecord addEvent(Instant at, String eventName, ObjectNode data, long nowNs) {
        long seq = ++eventSeq;
        data.put("event", eventName);
        JobEventRecord record = new JobEventRecord(seq, at, id, JobEventRecord.typeOf(eventName), data);
        events.add(record);
        updatedNs = nowNs;
        return record;
    }

    public synchronized long eventSeq() {
        return eventSeq;
    }

    public synchronized void touch(long nowNs) {
        this.updatedNs = nowNs;
    }

    public synchronized void status(JobStatus next, long nowNs) {
        this.status = next;
        this.updatedNs = nowNs;
    }

    public synchronized void input(ObjectNode edited) {
        this.input = edited;
    }

    public synchronized void preview(JsonNode value) {
        this.preview = value;
    }

    public synchronized void policy(JsonNode value) {
        this.policy = value;
    }

    public synchronized void batch(BatchState state) {
        this.batch = state;
    }

    public synchronized void attention(AttentionRecord record) {
        this.attention = record;
    }

    public synchronized void started(Instant at, long nowNs) {
        this.startedAt = at;
        this.status = JobStatus.RUNNING;
        this.updatedNs = nowNs;
    }

    public synchronized void finished(JobStatus terminal, Instant at, ObjectNode result, String failedStepId, String failure, long nowNs) {
        this.status = terminal;
        this.endedAt = at;
        this.output = result;
        this.failedStep = failedStepId;
        this.errorClass = failure;
        this.attention = null;
        this.updatedNs = nowNs;
    }

    public synchronized Long durationMs() {
        if (startedAt == null || endedAt == null) {
            return null;
        }
        return Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli());
    }

    public synchronized boolean finished() {
        return status.terminal();
    }

    // pausing and steering

    /** The future the interpreter blocks on while a person decides; one at a time. */
    public synchronized CompletableFuture<Decision> pause() {
        this.pending = new CompletableFuture<>();
        return pending;
    }

    public synchronized void resumed() {
        this.pending = null;
    }

    /** Hands a decision to the paused interpreter; false when the job is not paused. */
    public synchronized boolean decide(Decision decision) {
        if (pending == null || pending.isDone()) {
            return false;
        }
        pending.complete(decision);
        return true;
    }

    public synchronized boolean paused() {
        return pending != null && !pending.isDone();
    }

    public synchronized void runner(Thread thread) {
        this.runner = thread;
    }

    /** Asks the running job to stop: the paused future ends, the runner thread is interrupted. */
    public synchronized void requestCancel() {
        this.cancelRequested = true;
        if (pending != null && !pending.isDone()) {
            pending.complete(new Decision("cancel", null, null, null));
        }
        if (runner != null) {
            runner.interrupt();
        }
    }

    public synchronized boolean cancelRequested() {
        return cancelRequested;
    }

    // documents

    /** The JobSummary document. */
    public synchronized ObjectNode summary() {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("id", id);
        out.put("workflow", workflow);
        out.put("title", title);
        out.put("version", version);
        out.put("status", status.id());
        out.put("requester", requester);
        out.put("channel", channel);
        if (startedAt != null) {
            out.put("startedAt", startedAt.toString());
        }
        if (endedAt != null) {
            out.put("endedAt", endedAt.toString());
            out.put("durationMs", durationMs());
        }
        if (parentId != null) {
            out.put("parentId", parentId);
        }
        out.put("kind", kind);
        if (batch != null) {
            out.set("progress", batch.progress());
        }
        if (row != null) {
            out.put("row", row);
        }
        if (errorClass != null) {
            out.put("errorClass", errorClass);
        }
        if (failedStep != null) {
            out.put("failedStep", failedStep);
        }
        if (actor != null && !actor.equals(requester)) {
            out.put("actor", actor);
        }
        if (onBehalfOf != null) {
            out.set("onBehalfOf", onBehalfOf.deepCopy());
        }
        if (context != null) {
            out.set("context", context.document());
        }
        return out;
    }

    /** The JobDetail document; {@code masked} hides input and output values from a caller that does not own the job (P-18). */
    public synchronized ObjectNode detail(boolean masked, List<ObjectNode> children) {
        ObjectNode out = summary();
        out.set("input", masked ? mask(input) : (input == null ? JsonNodeFactory.instance.objectNode() : input.deepCopy()));
        if (output != null) {
            out.set("output", masked ? mask(output) : output.deepCopy());
        }
        var stepsNode = out.putArray("steps");
        for (StepRecord step : steps.values()) {
            ObjectNode doc = step.document();
            if (masked) {
                doc.remove("input");
                doc.remove("output");
            }
            stepsNode.add(doc);
        }
        var approvalsNode = out.putArray("approvals");
        approvals.forEach(a -> approvalsNode.add(a.document()));
        if (preview != null && !preview.isNull()) {
            out.set("preview", preview);
        }
        if (changeId != null) {
            ObjectNode change = out.putObject("change");
            change.put("id", changeId);
            change.put("state", "implementing");
        }
        if (children != null && !children.isEmpty()) {
            var childrenNode = out.putArray("children");
            children.forEach(childrenNode::add);
        }
        if (batch != null) {
            out.set("batch", batch.document());
        }
        if (attention != null) {
            out.set("attention", attention.document());
        }
        if (traceId != null) {
            out.put("traceId", traceId);
        }
        out.putObject("links");
        return out;
    }

    /** Field names kept, values replaced by {@code ***}. */
    public static ObjectNode mask(ObjectNode node) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        if (node != null) {
            node.fieldNames().forEachRemaining(name -> out.put(name, "***"));
        }
        return out;
    }
}
