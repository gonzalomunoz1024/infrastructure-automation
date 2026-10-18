package com.iaf.platform.runs.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderDefinition.Action;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceCommands;
import com.iaf.platform.resources.ports.inbound.ResourceCommands.RegisterResource;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.ChildFailure;
import com.iaf.platform.runs.domain.ChildResults;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobCancelled;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.MiniScript;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.runs.domain.StepExecutor;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCall;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.DependencyRules;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Mono;

/**
 * The {@link StepExecutor} of a real job: provider calls, automation, resources and children go
 * through the outbound ports; approvals, attention, sleeps, children and fork branches through
 * the {@link Engine} (per-job latches in the in-memory engine, signals and timers in Temporal);
 * every step event becomes a {@code job.step} event. In dry-run mode nothing is published, the
 * ports are called with {@code dryRun = true}, approvals are approved on the spot, waits are
 * skipped, attention aborts, and children walk in process; the events go to a recorder instead.
 */
public class PortStepExecutor implements StepExecutor {

    public static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(15);
    public static final Duration USE_POLL = Duration.ofSeconds(5);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** What the walker needs from the engine that hosts it. */
    public interface Engine {
        Optional<ApprovalDecision> awaitDecision(JobContext ctx, String approvalId, Duration timeout);

        Optional<AttentionDecision> awaitAttention(JobContext ctx, Attention attention, Duration timeout);

        /** Sleep, waking early (with a {@link JobCancelled}) when the job is cancelled. */
        void sleep(JobContext ctx, Duration duration);

        boolean cancelled(JobContext ctx);

        /** Run a child job to its end and return {@code { jobId, status, outputs }}; a failed child is a {@link ChildFailure}. */
        JsonNode runChild(JobContext ctx, Step step, WorkflowDefinition child, JsonNode input, Duration timeout);

        List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches);
    }

    /** Where dry-run step events go: {@code prefix} names the run steps above a child's step ({@code deploy/createVm}). */
    @FunctionalInterface
    public interface Recorder {
        void record(String prefix, StepEvent event);
    }

    /** The beans the executor works with. */
    public record Ports(ProviderQueries providers, ProviderCallPort calls, AutomationRunners runners, WorkflowQueries workflows,
            ResourceQueries resources, ResourceCommands resourceCommands, JobEvents events, Clock clock) {
    }

    private final Ports ports;
    private final Engine engine;
    private final Job job;
    private final boolean dryRun;
    private final Recorder recorder;
    private final String prefix;
    private final Set<String> announced = new HashSet<>();
    private final boolean suppressReplayed;

    /** A live executor for one job. */
    public PortStepExecutor(Ports ports, Engine engine, Job job) {
        this(ports, engine, job, false, null, "");
    }

    PortStepExecutor(Ports ports, Engine engine, Job job, boolean dryRun, Recorder recorder, String prefix) {
        this.ports = ports;
        this.engine = engine == null ? new DryRunEngine() : engine;
        this.job = job;
        this.dryRun = dryRun;
        this.recorder = recorder;
        this.prefix = prefix == null ? "" : prefix;
        if (job.attention() != null && job.resume() != null) {
            announced.add(job.attention().step());
        }
        this.suppressReplayed = job.resume() != null && !job.steps().isEmpty();
    }

    /** A dry-run executor over the same ports: nothing is published or written. */
    public static PortStepExecutor dryRun(Ports ports, Job job, Recorder recorder) {
        return new PortStepExecutor(ports, null, job, true, recorder, "");
    }

    public Job job() {
        return job;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    // ---------------------------------------------------------------- calls

    @Override
    public JsonNode callProvider(JobContext ctx, Step step, String provider, String action, JsonNode input, Duration timeout) {
        if (provider == null || provider.isBlank()) {
            throw new StepFailure(step.id(), "invalid_input", "call: provider needs with.provider", false, true);
        }
        ProviderDefinition p = block(ports.providers().provider(provider), LOOKUP_TIMEOUT, step)
                .orElseThrow(() -> new StepFailure(step.id(), "unknown_provider", "Unknown provider '" + provider + "'", false));
        Action a = p.action(action == null ? "" : action)
                .orElseThrow(() -> new StepFailure(step.id(), "unknown_action", "Provider " + provider + " has no action '" + action + "'", false));
        JsonNode in = input == null || input.isMissingNode() ? F.objectNode() : input;
        try {
            JsonNode out = ports.calls().call(new ProviderCall(ctx.jobId(), ctx.nextSeq(), p, a, ctx.environment(), in, dryRun))
                    .block(timeout == null ? DEFAULT_CALL_TIMEOUT : timeout);
            return out == null ? F.objectNode() : out;
        } catch (ProviderCallException e) {
            throw new StepFailure(step.id(), e.errorClass(), e.getMessage(), e.retryable());
        } catch (StepFailure | JobCancelled e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(step, e, a.retrySafe());
        }
    }

    @Override
    public JsonNode runAutomation(JobContext ctx, Step step, JsonNode with, Duration timeout) {
        return automation(ctx, step, step.type(), with, timeout);
    }

    @Override
    public JsonNode notify(JobContext ctx, Step step, String channel, String message) {
        ObjectNode with = F.objectNode();
        with.put("channel", channel);
        with.put("message", message);
        return automation(ctx, step, StepType.NOTIFY, with, null);
    }

    @Override
    public JsonNode script(JobContext ctx, Step step, String language, String code, JsonNode input, Duration timeout) {
        ObjectNode with = F.objectNode();
        with.put("language", language == null ? "javascript" : language);
        with.put("code", code);
        with.set("input", input == null ? F.objectNode() : input);
        return automation(ctx, step, StepType.SCRIPT, with, timeout);
    }

    private JsonNode automation(JobContext ctx, Step step, StepType type, JsonNode with, Duration timeout) {
        AutomationRunnerPort runner = ports.runners().runnerFor(type)
                .orElseThrow(() -> new StepFailure(step.id(), "unsupported", "No automation runner supports '" + type.key() + "' steps", false, true));
        JsonNode w = with == null ? F.objectNode() : with;
        boolean checkMode = w.path("checkModeOnTest").asBoolean(false) && "test".equals(ctx.environment());
        try {
            JsonNode out = runner.run(new RunRequest(ctx.jobId(), ctx.nextSeq(), type, step, w, ctx.environment(), dryRun, checkMode))
                    .block(timeout == null ? DEFAULT_CALL_TIMEOUT : timeout);
            return out == null ? F.objectNode() : out;
        } catch (ProviderCallException e) {
            throw new StepFailure(step.id(), e.errorClass(), e.getMessage(), e.retryable());
        } catch (MiniScript.ScriptException e) {
            throw new StepFailure(step.id(), "script_error", e.getMessage(), false);
        } catch (StepFailure | JobCancelled e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new StepFailure(step.id(), "invalid_input", e.getMessage(), false);
        } catch (RuntimeException e) {
            throw translate(step, e, false);
        }
    }

    @Override
    public JsonNode runChild(JobContext ctx, Step step, String workflow, String version, JsonNode input, Duration timeout) {
        if (workflow == null || workflow.isBlank()) {
            throw new StepFailure(step.id(), "invalid_input", "run needs a workflow", false, true);
        }
        String range = version == null || version.isBlank() ? currentMajor(ctx, step, workflow) : version;
        WorkflowDefinition child = block(ports.workflows().resolve(workflow, range), LOOKUP_TIMEOUT, step)
                .map(WorkflowRecord::definition)
                .orElseThrow(() -> new StepFailure(step.id(), "unknown_workflow", "No published version of '" + workflow + "' satisfies " + range, false));
        // the child gets its schema's defaults, as a start through its own route would
        JsonNode in = InputDefaults.apply(child.inputSchema(), input == null || input.isMissingNode() ? F.objectNode() : input);
        return engine.runChild(ctx, step, child, in, timeout);
    }

    /**
     * The range of a run step that names none: the producer's current major ({@code ^2}), the
     * range a publish writes into the file, so a new major of the producer never reaches it.
     */
    private String currentMajor(JobContext ctx, Step step, String workflow) {
        String range = block(ports.workflows().published(workflow), LOOKUP_TIMEOUT, step)
                .map(r -> Optional.ofNullable(DependencyRules.defaultRange(r.version())).orElse(r.version()))
                .orElseThrow(() -> new StepFailure(step.id(), "unknown_workflow", "'" + workflow + "' has no published version", false));
        log(ctx, step.id() + ": no version given, running " + workflow + " " + range + " (its current major)");
        return range;
    }

    // ---------------------------------------------------------------- people

    @Override
    public String requestApproval(JobContext ctx, Step step, ApprovalRequest request) {
        String id = Ids.next("apr");
        if (dryRun || job.preApproved()) {
            return id;
        }
        Approval a = Approval.builder()
                .id(id)
                .jobId(ctx.jobId())
                .workflow(ctx.workflow())
                .step(step.id())
                .group(request.group())
                .message(request.message())
                .requestedAt(now())
                .timeout(request.timeout() == null ? Durations.format(WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT) : request.timeout())
                .status(Approval.Status.PENDING)
                .preview(job.preview())
                .build();
        ports.events().approvalRequested(a);
        return id;
    }

    @Override
    public Optional<ApprovalDecision> awaitDecision(JobContext ctx, Step step, String approvalId, Duration timeout) {
        if (dryRun) {
            return Optional.of(ApprovalDecision.approved("dry-run", "approved by the dry run", now()));
        }
        if (job.preApproved()) {
            return Optional.of(ApprovalDecision.approved(job.requesterId(), "approved once for the whole batch", now()));
        }
        return engine.awaitDecision(ctx, approvalId, timeout);
    }

    @Override
    public void escalateApproval(JobContext ctx, Step step, String approvalId, String to) {
        if (!dryRun) {
            ports.events().approvalEscalated(ctx.jobId(), approvalId, step.id(), to);
        }
    }

    @Override
    public Optional<AttentionDecision> awaitAttention(JobContext ctx, Step step, Attention attention) {
        if (dryRun) {
            return Optional.of(AttentionDecision.abort("dry-run"));
        }
        if (!announced.remove(step.id())) {
            ports.events().attention(ctx.jobId(), attention);
        }
        Duration total = Durations.parse(attention.timeout(), Durations.parse(Attention.DEFAULT_TIMEOUT));
        Duration elapsed = attention.since() == null ? Duration.ZERO : Duration.between(attention.since(), now());
        Duration remaining = total.minus(elapsed);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        Optional<AttentionDecision> decision = engine.awaitAttention(ctx, attention, remaining);
        decision.ifPresent(d -> ports.events().attentionResolved(ctx.jobId(), step.id(), d.action().json(), d.by()));
        return decision;
    }

    @Override
    public void sleep(JobContext ctx, Duration duration) {
        if (!dryRun && duration != null && !duration.isNegative() && !duration.isZero()) {
            engine.sleep(ctx, duration);
        }
    }

    // ---------------------------------------------------------------- resources

    @Override
    public JsonNode useResource(JobContext ctx, Step step, UseRequest request, Duration timeout) {
        if (request.id() == null || request.id().isBlank()) {
            throw new StepFailure(step.id(), "invalid_input", "use needs an id", false, true);
        }
        Duration limit = timeout == null ? DEFAULT_CALL_TIMEOUT : timeout;
        Instant deadline = now().plus(limit);
        while (true) {
            Resource r;
            try {
                r = block(ports.resources().use(request.type(), request.id(), ctx.identity(), request.checkAccess()), LOOKUP_TIMEOUT, step)
                        .orElseThrow(() -> new StepFailure(step.id(), "not_found", "No " + request.type() + " with id " + request.id(), false));
            } catch (ForbiddenException e) {
                throw new StepFailure(step.id(), "forbidden", e.getMessage(), false, true);
            }
            if (!request.waitUntilReady() || "ready".equals(r.status()) || dryRun) {
                return JobViews.MAPPER.valueToTree(r);
            }
            if (!now().isBefore(deadline)) {
                throw new StepFailure(step.id(), "timeout", request.type() + " " + request.id() + " was not ready within " + Durations.format(limit)
                        + " (status " + r.status() + ")", true);
            }
            if (engine.cancelled(ctx)) {
                throw new JobCancelled(ctx.jobId());
            }
            engine.sleep(ctx, USE_POLL);
        }
    }

    @Override
    public JsonNode registerResource(JobContext ctx, Step step, RegisterRequest request) {
        if (request.type() == null || request.type().isBlank()) {
            throw new StepFailure(step.id(), "invalid_input", "register needs a type", false, true);
        }
        if (dryRun) {
            String id = request.id() == null || request.id().isBlank() ? request.type() + "-" + ctx.nextSeq() : request.id();
            ObjectNode r = F.objectNode();
            r.put("id", id);
            r.put("type", request.type());
            r.put("name", id);
            r.put("status", request.state() == null ? "ready" : request.state());
            r.set("attributes", request.attributes() == null ? F.objectNode() : request.attributes());
            ArrayNode from = r.putArray("builtFrom");
            if (request.builtFrom() != null) {
                request.builtFrom().forEach(from::add);
            }
            r.put("dryRun", true);
            return r;
        }
        Resource r = block(ports.resourceCommands().register(new RegisterResource(request.type(), request.id(), request.state(), request.attributes(),
                request.builtFrom(), ctx.jobId(), ctx.workflow(), ctx.version(), ctx.identity())), LOOKUP_TIMEOUT, step)
                .orElseThrow(() -> new StepFailure(step.id(), "registry_error", "The registry did not return the resource", true));
        return JobViews.MAPPER.valueToTree(r);
    }

    // ---------------------------------------------------------------- events and clock

    @Override
    public void onStepEvent(JobContext ctx, StepEvent event) {
        if (recorder != null) {
            recorder.record(prefix, event);
            return;
        }
        if (dryRun) {
            return;
        }
        if (event.replayed() && suppressReplayed) {
            return;
        }
        ports.events().step(ctx.jobId(), event);
    }

    @Override
    public void log(JobContext ctx, String message) {
        if (!dryRun) {
            ports.events().log(ctx.jobId(), message);
        }
    }

    @Override
    public Instant now() {
        return ports.clock() == null ? Instant.now() : ports.clock().instant();
    }

    @Override
    public boolean cancelled(JobContext ctx) {
        return engine.cancelled(ctx);
    }

    @Override
    public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
        return engine.runBranches(ctx, step, branches);
    }

    // ---------------------------------------------------------------- helpers

    private static <T> Optional<T> block(Mono<T> mono, Duration timeout, Step step) {
        try {
            return mono.blockOptional(timeout);
        } catch (StepFailure | JobCancelled | ForbiddenException e) {
            throw e;
        } catch (RuntimeException e) {
            throw translate(step, e, true);
        }
    }

    private static StepFailure translate(Step step, RuntimeException e, boolean retryable) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof IllegalStateException && msg.contains("Timeout")) {
            return new StepFailure(step.id(), "timeout", "Step " + step.id() + " timed out", true);
        }
        if (e.getCause() instanceof ProviderCallException p) {
            return new StepFailure(step.id(), p.errorClass(), p.getMessage(), p.retryable());
        }
        return new StepFailure(step.id(), "error", msg, retryable);
    }

    /** The engine of a dry run: approvals pass, attention aborts, waits are skipped, children walk in process. */
    private final class DryRunEngine implements Engine {
        @Override
        public Optional<ApprovalDecision> awaitDecision(JobContext ctx, String approvalId, Duration timeout) {
            return Optional.of(ApprovalDecision.approved("dry-run", null, now()));
        }

        @Override
        public Optional<AttentionDecision> awaitAttention(JobContext ctx, Attention attention, Duration timeout) {
            return Optional.of(AttentionDecision.abort("dry-run"));
        }

        @Override
        public void sleep(JobContext ctx, Duration duration) {
        }

        @Override
        public boolean cancelled(JobContext ctx) {
            return false;
        }

        @Override
        public JsonNode runChild(JobContext ctx, Step step, WorkflowDefinition child, JsonNode input, Duration timeout) {
            Job childJob = JobFactory.child(job, child, input, now());
            JobContext cctx = JobContext.of(childJob);
            ctx.addChild(childJob.id());
            PortStepExecutor childExecutor = new PortStepExecutor(ports, null, childJob, true, recorder, prefix + step.id() + "/");
            var outcome = new WorkflowInterpreter(child, childExecutor, cctx).run();
            if (!outcome.succeeded()) {
                throw ChildFailure.of(step.id(), childJob.id(), child.name(), outcome);
            }
            return ChildResults.of(childJob.id(), outcome, cctx);
        }

        @Override
        public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
            List<JsonNode> out = new ArrayList<>();
            RuntimeException first = null;
            for (Supplier<JsonNode> b : branches) {
                try {
                    out.add(b.get());
                } catch (RuntimeException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            if (first != null) {
                throw first;
            }
            return out;
        }
    }
}
