package com.iaf.platform.runs.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.runs.domain.RecordedWorld.Answer;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * A {@link StepExecutor} over the recorded world alone: no ports, no threads, no clock. Provider
 * calls answer from samples, automation steps from the matching provider file, children run as
 * nested walks, approvals are decided by a pluggable approver (auto-approve by default), a
 * failing step can be planned per step id, resources live in a small in-memory registry and the
 * clock is virtual (each call advances it). The demo seed and the interpreter tests run on it.
 */
public class SampleStepExecutor implements StepExecutor {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Resolves {@code run} steps: the child definition for a workflow name and a version range. */
    @FunctionalInterface
    public interface ChildResolver {
        Optional<WorkflowDefinition> resolve(String workflow, String range);
    }

    /** A step event with the job it belongs to (children share the executor). */
    public record Recorded(String jobId, StepEvent event) {
    }

    /** A child run, for the tests and the seed. */
    public record ChildRun(String jobId, String parentId, WorkflowDefinition definition, JsonNode input, Outcome outcome, Instant startedAt, Instant endedAt) {
    }

    /** A provider call that happened, for telemetry. */
    public record Call(Instant at, String jobId, String workflow, String version, String provider, String action, String environment,
            boolean ok, long durationMs, String errorClass) {
    }

    protected final Map<String, ProviderDefinition> providers = new LinkedHashMap<>();
    protected final List<ProviderDefinition> providerList = new ArrayList<>();
    protected final Map<String, ResourceTypeDefinition> types = new LinkedHashMap<>();
    protected final ChildResolver children;
    protected final RecordedWorld world = new RecordedWorld();
    protected final Map<String, JsonNode> registry = new ConcurrentHashMap<>();
    protected final List<Recorded> events = Collections.synchronizedList(new ArrayList<>());
    protected final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    protected final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    protected final Map<String, ApprovalRequest> approvals = new LinkedHashMap<>();
    protected final List<Attention> attentions = Collections.synchronizedList(new ArrayList<>());
    protected final List<ChildRun> childRuns = Collections.synchronizedList(new ArrayList<>());
    protected final Map<String, Deque<StepFailure>> plannedFailures = new ConcurrentHashMap<>();
    protected final AtomicInteger counter = new AtomicInteger();
    protected Function<ApprovalRequest, Optional<ApprovalDecision>> approver;
    protected Function<Attention, Optional<AttentionDecision>> attender = a -> Optional.of(AttentionDecision.abort("nobody"));
    protected Function<Step, Duration> latency = s -> Duration.ofMillis(60);
    protected Instant clock;

    public SampleStepExecutor(List<ProviderDefinition> providers, ChildResolver children) {
        this(providers, children, List.of(), Instant.parse("2026-10-17T10:00:00Z"));
    }

    public SampleStepExecutor(List<ProviderDefinition> providers, ChildResolver children, List<ResourceTypeDefinition> types, Instant clock) {
        providers.forEach(p -> {
            this.providers.put(p.name(), p);
            this.providerList.add(p);
        });
        types.forEach(t -> this.types.put(t.name(), t));
        this.children = children == null ? (w, r) -> Optional.empty() : children;
        this.clock = clock;
        this.approver = req -> Optional.of(ApprovalDecision.approved("approver", null, now()));
    }

    // ---------------------------------------------------------------- configuration

    public SampleStepExecutor approver(Function<ApprovalRequest, Optional<ApprovalDecision>> f) {
        this.approver = f;
        return this;
    }

    public SampleStepExecutor attender(Function<Attention, Optional<AttentionDecision>> f) {
        this.attender = f;
        return this;
    }

    public SampleStepExecutor latency(Function<Step, Duration> f) {
        this.latency = f;
        return this;
    }

    /** Plan a failure for the next {@code times} invocations of a step. */
    public SampleStepExecutor failAt(String stepId, String errorClass, String message, boolean retryable, int times) {
        Deque<StepFailure> q = plannedFailures.computeIfAbsent(stepId, k -> new ArrayDeque<>());
        for (int i = 0; i < times; i++) {
            q.add(new StepFailure(stepId, errorClass, message, retryable));
        }
        return this;
    }

    public SampleStepExecutor failAt(String stepId, String errorClass, String message) {
        return failAt(stepId, errorClass, message, false, 1);
    }

    public SampleStepExecutor failAt(String stepId, String errorClass, String message, boolean retryable) {
        return failAt(stepId, errorClass, message, retryable, 1);
    }

    /** Put a resource in the registry so {@code use} finds it. */
    public SampleStepExecutor resource(String type, String id, String status, JsonNode attributes) {
        registry.put(type + ":" + id, resourceJson(type, id, status, attributes, List.of(), null, null));
        return this;
    }

    // ---------------------------------------------------------------- what happened

    public List<Recorded> events() {
        return List.copyOf(events);
    }

    public List<StepEvent> events(String jobId) {
        return events.stream().filter(r -> r.jobId().equals(jobId)).map(Recorded::event).toList();
    }

    public List<String> logs() {
        return List.copyOf(logs);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public List<Attention> attentions() {
        return List.copyOf(attentions);
    }

    public Map<String, ApprovalRequest> approvals() {
        return Map.copyOf(approvals);
    }

    public List<ChildRun> childRuns() {
        return List.copyOf(childRuns);
    }

    public JsonNode resource(String type, String id) {
        return registry.get(type + ":" + id);
    }

    public RecordedWorld world() {
        return world;
    }

    // ---------------------------------------------------------------- StepExecutor

    @Override
    public JsonNode callProvider(JobContext ctx, Step step, String provider, String action, JsonNode input, Duration timeout) {
        ProviderDefinition p = providers.get(provider);
        if (p == null) {
            planned(step);
            throw new StepFailure(step.id(), "unknown_provider", "Unknown provider '" + provider + "'", false);
        }
        Action a = p.action(action == null ? "" : action).orElseThrow(() -> new StepFailure(step.id(), "unknown_action", "Provider " + provider + " has no action '" + action + "'", false));
        Instant at = now();
        Duration took = tick(step);
        StepFailure failure = plannedFailure(step);
        if (failure != null) {
            // a planned failure is still a call the provider answered badly: it shows in the telemetry
            calls.add(new Call(at, ctx.jobId(), ctx.workflow(), ctx.version(), provider, action, ctx.environment(), false, took.toMillis(), failure.errorClass()));
            throw failure;
        }
        try {
            JsonNode out = world.call(p, a, input, ctx.jobId(), ctx.nextSeq(), true);
            calls.add(new Call(at, ctx.jobId(), ctx.workflow(), ctx.version(), provider, action, ctx.environment(), true, took.toMillis(), null));
            return out;
        } catch (ProviderCallException e) {
            calls.add(new Call(at, ctx.jobId(), ctx.workflow(), ctx.version(), provider, action, ctx.environment(), false, took.toMillis(), e.errorClass()));
            throw new StepFailure(step.id(), e.errorClass(), e.getMessage(), e.retryable());
        }
    }

    @Override
    public JsonNode runAutomation(JobContext ctx, Step step, JsonNode with, Duration timeout) {
        Instant at = now();
        Duration took = tick(step);
        boolean checkMode = with != null && with.path("checkModeOnTest").asBoolean(false) && "test".equals(ctx.environment());
        try {
            Answer answer = RecordedWorld.automation(providerList, step.type(), with, ctx.jobId(), ctx.nextSeq(), checkMode);
            StepFailure failure = plannedFailure(step);
            if (answer.provider() != null) {
                calls.add(new Call(at, ctx.jobId(), ctx.workflow(), ctx.version(), answer.provider().name(), answer.action().id(), ctx.environment(),
                        failure == null, took.toMillis(), failure == null ? null : failure.errorClass()));
            }
            if (failure != null) {
                throw failure;
            }
            return answer.output();
        } catch (MiniScript.ScriptException e) {
            throw new StepFailure(step.id(), "script_error", e.getMessage(), false);
        }
    }

    @Override
    public JsonNode runChild(JobContext ctx, Step step, String workflow, String version, JsonNode input, Duration timeout) {
        planned(step);
        WorkflowDefinition child = children.resolve(workflow, version)
                .orElseThrow(() -> new StepFailure(step.id(), "unknown_workflow", "No published version of '" + workflow + "' satisfies " + version, false));
        String childId = ctx.jobId() + "-" + counter.incrementAndGet();
        Instant started = now();
        JobContext cctx = new JobContext(childId, child.name(), child.version(), input, ctx.identity(), ctx.environment(), started, ctx.traceId(), ctx.jobId());
        ctx.addChild(childId);
        Outcome o = new WorkflowInterpreter(child, this, cctx).run();
        childRuns.add(new ChildRun(childId, ctx.jobId(), child, input, o, started, now()));
        if (!o.succeeded()) {
            throw new StepFailure(step.id(), o.errorClass() == null ? "child_failed" : o.errorClass(),
                    "Child job " + childId + " (" + workflow + ") " + o.status().json() + (o.message() == null ? "" : ": " + o.message()), false);
        }
        return childResult(childId, o);
    }

    public static JsonNode childResult(String childId, Outcome o) {
        ObjectNode out = F.objectNode();
        out.put("jobId", childId);
        out.put("status", o.status().json());
        out.set("outputs", o.output() == null ? F.objectNode() : o.output());
        return out;
    }

    @Override
    public String requestApproval(JobContext ctx, Step step, ApprovalRequest request) {
        String id = "apr_" + ctx.jobId() + "_" + step.id();
        approvals.put(id, request);
        return id;
    }

    @Override
    public Optional<ApprovalDecision> awaitDecision(JobContext ctx, Step step, String approvalId, Duration timeout) {
        return approver.apply(approvals.get(approvalId));
    }

    @Override
    public void escalateApproval(JobContext ctx, Step step, String approvalId, String to) {
        logs.add("escalated " + approvalId + " to " + to);
    }

    @Override
    public Optional<AttentionDecision> awaitAttention(JobContext ctx, Step step, Attention attention) {
        attentions.add(attention);
        return attender.apply(attention);
    }

    @Override
    public void sleep(JobContext ctx, Duration duration) {
        clock = clock.plus(duration);
    }

    @Override
    public JsonNode useResource(JobContext ctx, Step step, UseRequest request, Duration timeout) {
        planned(step);
        tick(step);
        JsonNode r = registry.get(request.type() + ":" + request.id());
        if (r == null) {
            r = synthesize(request.type(), request.id());
            registry.put(request.type() + ":" + request.id(), r);
        }
        return r;
    }

    @Override
    public JsonNode registerResource(JobContext ctx, Step step, RegisterRequest request) {
        planned(step);
        tick(step);
        String id = request.id() == null || request.id().isBlank() ? request.type() + "-" + ctx.nextSeq() : request.id();
        JsonNode existing = registry.get(request.type() + ":" + id);
        ObjectNode attributes = F.objectNode();
        if (existing != null && existing.path("attributes").isObject()) {
            attributes.setAll((ObjectNode) existing.get("attributes"));
        }
        if (request.attributes() != null && request.attributes().isObject()) {
            attributes.setAll((ObjectNode) request.attributes());
        }
        String status = request.state() != null ? request.state() : existing != null ? existing.path("status").asText("ready") : "ready";
        JsonNode r = resourceJson(request.type(), id, status, attributes, request.builtFrom(), ctx.jobId(), ctx.workflow());
        registry.put(request.type() + ":" + id, r);
        return r;
    }

    @Override
    public JsonNode notify(JobContext ctx, Step step, String channel, String message) {
        planned(step);
        tick(step);
        ObjectNode out = F.objectNode();
        out.put("delivered", true);
        out.put("channel", channel);
        out.put("message", message);
        return out;
    }

    @Override
    public JsonNode script(JobContext ctx, Step step, String language, String code, JsonNode input, Duration timeout) {
        planned(step);
        tick(step);
        try {
            return MiniScript.run(code, input);
        } catch (MiniScript.ScriptException e) {
            throw new StepFailure(step.id(), "script_error", e.getMessage(), false);
        }
    }

    @Override
    public void onStepEvent(JobContext ctx, StepEvent event) {
        events.add(new Recorded(ctx.jobId(), event));
    }

    @Override
    public void log(JobContext ctx, String message) {
        logs.add(message);
    }

    @Override
    public Instant now() {
        return clock;
    }

    // ---------------------------------------------------------------- helpers

    protected void planned(Step step) {
        StepFailure failure = plannedFailure(step);
        if (failure != null) {
            tick(step);
            throw failure;
        }
    }

    /** The next planned failure of a step, consumed; null when none is planned. */
    protected StepFailure plannedFailure(Step step) {
        Deque<StepFailure> q = plannedFailures.get(step.id());
        return q == null || q.isEmpty() ? null : q.poll();
    }

    protected Duration tick(Step step) {
        Duration d = latency.apply(step);
        clock = clock.plus(d);
        return d;
    }

    protected JsonNode synthesize(String type, String id) {
        ResourceTypeDefinition t = types.get(type);
        ObjectNode attributes = F.objectNode();
        if (t != null && t.schema() != null) {
            JsonNode sample = Schemas.sample(t.schema());
            if (sample.isObject()) {
                attributes.setAll((ObjectNode) sample);
            }
        }
        attributes.put("name", id);
        return resourceJson(type, id, "ready", attributes, List.of(), null, null);
    }

    public static JsonNode resourceJson(String type, String id, String status, JsonNode attributes, List<String> builtFrom, String jobId, String workflow) {
        ObjectNode r = F.objectNode();
        r.put("id", id);
        r.put("type", type);
        JsonNode attrs = attributes == null ? F.objectNode() : attributes;
        r.put("name", attrs.path("name").asText(id));
        r.put("status", status == null ? "ready" : status);
        r.set("attributes", attrs);
        ArrayNode bf = r.putArray("builtFrom");
        if (builtFrom != null) {
            builtFrom.forEach(bf::add);
        }
        if (jobId != null) {
            ObjectNode by = r.putObject("createdBy");
            by.put("jobId", jobId);
            by.put("workflow", workflow);
        }
        return r;
    }
}
