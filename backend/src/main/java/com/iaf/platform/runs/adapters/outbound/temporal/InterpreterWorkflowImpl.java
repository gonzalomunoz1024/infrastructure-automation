package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.runs.application.PortStepExecutor;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobCancelled;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.runs.domain.StepExecutor;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.ApprovalPolicy;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.Async;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowThreadLocal;

/**
 * The production engine: the {@link WorkflowInterpreter} walking inside a Temporal workflow, with
 * this class as its {@link StepExecutor}. Everything that touches the world is an activity
 * ({@link StepActivities}); approvals and attention are {@code Workflow.await}s on the signals;
 * waits, retry backoffs and the polls of {@code use ... waitUntil: ready} are timers a cancel
 * wakes; fork branches are workflow threads ({@code Async.function}); a {@code run} step and
 * every batch row is a child workflow whose id is the child job's id; ids are minted by
 * activities or a side effect, never here. It mirrors {@code InMemoryWorkflowExecution} event
 * for event, so the projection, the audit log and the live streams cannot tell the engines apart.
 * The definition snapshot travels in the {@link Job} input, so a published version never changes
 * a running job.
 *
 * <p>Activities: the calls of a step run once with the step's timeout plus a grace (the
 * interpreter does its own retries and classifies the failure); records (an approval, a child
 * job) are created at most once; events are published with a few retries, since a lost event
 * would leave the projection behind. A cancel requested through Temporal itself (the UI, the
 * CLI) ends the job like the {@code cancel} signal does, by {@code temporal}; the execution then
 * closes as cancelled instead of completing with the outcome, as the SDK closes every workflow
 * whose cancel was requested, so the events, not the result, are what the platform reads.</p>
 *
 * <p>Team workers: the action of an sdk provider and a {@code call: activity} do not run on the
 * platform's activities but on the team's own worker, as one activity on its task queue
 * ({@link WorkerCalls} is the contract); a local activity looks up whether a provider is one. A
 * cancel of the job cancels such an activity at once. Executions recorded before this change
 * ({@value #TEAM_WORKERS}) replay on the platform's activities as they ran.</p>
 *
 * <p>Deterministic by construction: no clock but {@code Workflow.currentTimeMillis()}, no
 * threads, no Spring beans; the interpreter and the expression engine are pure.</p>
 */
public class InterpreterWorkflowImpl implements InterpreterWorkflow, StepExecutor {

    /** Added to a step's timeout for the activity's start-to-close, so the port's own timeout (and its error class) fires first. */
    static final Duration CALL_GRACE = Duration.ofMinutes(1);
    /** The start-to-close of an activity that publishes an event or creates a record. */
    static final Duration RECORD_TIMEOUT = Duration.ofMinutes(1);
    /** Who cancelled a job that Temporal itself cancelled. */
    static final String TEMPORAL = "temporal";
    /** The change id of sending team steps to the team's worker (version 1); executions without its marker ran them on the platform's activities. */
    static final String TEAM_WORKERS = "team-workers";
    private static final RetryOptions ONCE = RetryOptions.newBuilder().setMaximumAttempts(1).build();
    private static final RetryOptions PUBLISH = RetryOptions.newBuilder()
            .setMaximumAttempts(5)
            .setInitialInterval(Duration.ofSeconds(1))
            .setMaximumInterval(Duration.ofSeconds(30))
            .build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final Logger log = Workflow.getLogger(InterpreterWorkflowImpl.class);
    private final StepActivities events = Workflow.newActivityStub(StepActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(RECORD_TIMEOUT).setRetryOptions(PUBLISH).build());
    private final StepActivities records = stub(RECORD_TIMEOUT);
    private final StepActivities lookups = Workflow.newLocalActivityStub(StepActivities.class, LocalActivityOptions.newBuilder()
            .setStartToCloseTimeout(PortStepExecutor.LOOKUP_TIMEOUT)
            .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
            .build());
    /** Per workflow thread (each fork branch is one): the attempt of the step it runs, from the step's running event. */
    private final WorkflowThreadLocal<Attempts> attempts = WorkflowThreadLocal.withCachedInitial(Attempts::new);

    private Job job;
    private JobRef ref;
    private JobContext ctx;
    private JobStatus status = JobStatus.QUEUED;
    private String waitingOn;
    private boolean cancelled;
    private String cancelledBy;
    private final Map<String, ApprovalDecision> decisions = new HashMap<>();
    private AttentionDecision attentionDecision;
    private final Set<String> announced = new HashSet<>();
    private boolean suppressReplayed;

    // ---------------------------------------------------------------- the workflow

    @Override
    public Outcome run(Job job) {
        this.job = job;
        this.ref = JobRef.of(job);
        this.ctx = JobContext.of(job);
        if (job.attention() != null && job.resume() != null) {
            announced.add(job.attention().step());
        }
        suppressReplayed = job.resume() != null && !job.steps().isEmpty();
        status = JobStatus.RUNNING;
        Outcome outcome;
        try {
            outcome = job.isBatch() ? runBatch() : runSingle();
        } catch (JobCancelled e) {
            outcome = Outcome.cancelled();
        } catch (CanceledFailure e) {
            temporalCancel();
            outcome = Outcome.cancelled();
        } catch (RuntimeException e) {
            log.error("job {} crashed: {}", job.id(), e.toString(), e);
            outcome = new Outcome(JobStatus.FAILED, null, "engine_error", null, e.getMessage());
        }
        finish(outcome);
        return outcome;
    }

    @Override
    public void approve(String approvalId, ApprovalDecision decision) {
        if (approvalId != null && decision != null) {
            decisions.put(approvalId, decision);
        }
    }

    @Override
    public void attention(AttentionDecision decision) {
        // kept until the next awaitAttention consumes it: a decision that arrives early is not lost
        attentionDecision = decision;
    }

    @Override
    public void cancel(String by) {
        // only a flag: every wait wakes on it, and the waits on child workflows forward it to them
        if (!cancelled) {
            cancelled = true;
            cancelledBy = by;
        }
    }

    @Override
    public JobStatus status() {
        return status;
    }

    @Override
    public String waitingOn() {
        return waitingOn;
    }

    private Outcome runSingle() {
        if (job.definition() == null) {
            return new Outcome(JobStatus.FAILED, null, "no_definition", null, "The job carries no workflow definition");
        }
        if (cancelled) {
            return Outcome.cancelled();
        }
        return new WorkflowInterpreter(job.definition(), this, ctx, resumeOf(job)).run();
    }

    static WorkflowInterpreter.Resume resumeOf(Job job) {
        if (job.resume() == null || job.resume().fromStep() == null) {
            return null;
        }
        Approval pending = job.pendingApproval(job.resume().fromStep());
        return new WorkflowInterpreter.Resume(job.resume().fromStep(), job.resume().seed(), pending == null ? null : pending.id(), job.attention());
    }

    /** The final event, published even when Temporal cancelled the workflow: a cancelled scope refuses new activities, a detached one does not. */
    private void finish(Outcome outcome) {
        Instant at = now();
        String by = cancelledBy == null ? Identity.SYSTEM.id() : cancelledBy;
        try {
            Workflow.newDetachedCancellationScope(() -> {
                if (outcome.status() == JobStatus.CANCELLED) {
                    events.cancelled(ref, by, at);
                } else {
                    events.finished(ref, outcome, at);
                }
            }).run();
        } catch (ActivityFailure e) {
            log.error("job {} ended {} but its final event could not be published: {}", ref.id(), outcome.status().json(), e.toString());
        }
        status = outcome.status();
        waitingOn = null;
    }

    // ---------------------------------------------------------------- StepExecutor: calls

    @Override
    public JsonNode callProvider(JobContext ctx, Step step, String provider, String action, JsonNode input, Duration timeout) {
        StepCall call = call(step, timeout, true);
        JsonNode in = node(input);
        if (teamWorkers()) {
            WorkerRoute route = invoke(step, () -> lookups.providerRoute(step.id(), provider, action));
            if (route != null) {
                return onWorker(step, call, action, route, in);
            }
        }
        return invoke(step, () -> calls(timeout).callProvider(call, provider, action, in));
    }

    @Override
    public JsonNode runAutomation(JobContext ctx, Step step, JsonNode with, Duration timeout) {
        StepCall call = call(step, timeout, true);
        JsonNode w = node(with);
        if (step.type() == StepType.ACTIVITY && teamWorkers()) {
            String queue = w == null ? "" : w.path("taskQueue").asText("");
            String activity = w == null ? "" : w.path("activity").asText("");
            if (queue.isBlank() || activity.isBlank()) {
                throw new StepFailure(step.id(), "invalid_input", "call: activity needs with.taskQueue and with.activity", false, true);
            }
            return onWorker(step, call, activity, new WorkerRoute(queue, null), w.get("input"));
        }
        return invoke(step, () -> calls(timeout).runAutomation(call, w));
    }

    /** Whether this execution sends team steps to the team's worker: every new one does, one recorded before replays as it ran. */
    private static boolean teamWorkers() {
        return Workflow.getVersion(TEAM_WORKERS, Workflow.DEFAULT_VERSION, 1) >= 1;
    }

    /**
     * Run a step's call on a team's worker: one activity of {@code activityType} on the route's
     * queue with the {@link WorkerCalls} payload. The idempotency key is minted by the step's
     * first attempt and kept by its retries. A cancel of the job cancels the activity (the worker
     * sees it on its next heartbeat) and ends the step at once instead of after the call.
     */
    private JsonNode onWorker(Step step, StepCall call, String activityType, WorkerRoute route, JsonNode input) {
        Attempts a = attempts.get();
        int attempt = step.id().equals(a.step) ? Math.max(1, a.attempt) : 1;
        if (attempt == 1 || !a.firstSeq.containsKey(step.id())) {
            a.firstSeq.put(step.id(), call.seq());
        }
        String key = WorkerCalls.idempotencyKey(ref.id(), step.id(), a.firstSeq.get(step.id()));
        ObjectNode payload = WorkerCalls.payload(ref, step.id(), key, attempt, input);
        ActivityStub stub = Workflow.newUntypedActivityStub(WorkerCalls.options(route, call.timeout()));
        List<Promise<JsonNode>> result = new ArrayList<>(1);
        CancellationScope scope = Workflow.newCancellationScope(() -> {
            result.add(stub.executeAsync(activityType, JsonNode.class, payload));
        });
        scope.run();
        Promise<JsonNode> done = result.get(0);
        await(null, () -> done.isCompleted() || cancelled);
        if (!done.isCompleted()) {
            scope.cancel("job " + ref.id() + " was cancelled");
            throw new JobCancelled(ref.id());
        }
        try {
            JsonNode out = done.get();
            return out == null || out.isNull() ? F.objectNode() : out;
        } catch (ActivityFailure f) {
            if (f.getCause() instanceof CanceledFailure) {
                throw temporalCancel();
            }
            throw WorkerCalls.failure(step.id(), route, call.timeout(), f);
        } catch (CanceledFailure e) {
            throw temporalCancel();
        }
    }

    /** What a workflow thread knows about the step it runs: the current attempt, and the seq each step's first attempt minted. */
    private static final class Attempts {
        String step;
        int attempt;
        final Map<String, Integer> firstSeq = new HashMap<>();
    }

    @Override
    public JsonNode notify(JobContext ctx, Step step, String channel, String message) {
        StepCall call = call(step, null, true);
        return invoke(step, () -> calls(null).notify(call, channel, message));
    }

    @Override
    public JsonNode script(JobContext ctx, Step step, String language, String code, JsonNode input, Duration timeout) {
        StepCall call = call(step, timeout, true);
        JsonNode in = node(input);
        return invoke(step, () -> calls(timeout).script(call, language, code, in));
    }

    /**
     * {@code use}: one registry lookup per activity; with {@code waitUntil: ready} the pause
     * between lookups is a timer, so a cancel wakes it and a long wait costs no worker thread.
     */
    @Override
    public JsonNode useResource(JobContext ctx, Step step, UseRequest request, Duration timeout) {
        StepCall call = call(step, timeout, false);
        UseRequest lookup = new UseRequest(request.type(), request.id(), null, request.checkAccess());
        StepActivities activities = calls(PortStepExecutor.LOOKUP_TIMEOUT);
        Duration limit = timeout == null ? PortStepExecutor.DEFAULT_CALL_TIMEOUT : timeout;
        Instant deadline = now().plus(limit);
        while (true) {
            JsonNode resource = invoke(step, () -> activities.useResource(call, lookup));
            String state = resource == null ? null : resource.path("status").asText(null);
            if (!request.waitUntilReady() || "ready".equals(state)) {
                return resource;
            }
            if (!now().isBefore(deadline)) {
                throw new StepFailure(step.id(), "timeout", request.type() + " " + request.id() + " was not ready within " + Durations.format(limit)
                        + " (status " + state + ")", true);
            }
            if (cancelled) {
                throw new JobCancelled(ctx.jobId());
            }
            sleep(ctx, PortStepExecutor.USE_POLL);
        }
    }

    @Override
    public JsonNode registerResource(JobContext ctx, Step step, RegisterRequest request) {
        StepCall call = call(step, null, false);
        return invoke(step, () -> calls(PortStepExecutor.LOOKUP_TIMEOUT).registerResource(call, request));
    }

    @Override
    public JsonNode runChild(JobContext ctx, Step step, String workflow, String version, JsonNode input, Duration timeout) {
        if (workflow == null || workflow.isBlank()) {
            throw new StepFailure(step.id(), "invalid_input", "run needs a workflow", false, true);
        }
        String range = version == null || version.isBlank() ? "*" : version;
        JsonNode in = input == null || input.isMissingNode() ? F.objectNode() : input;
        Job child = invoke(step, () -> records.childJob(ref, step.id(), workflow, range, in));
        ctx.addChild(child.id());
        Outcome outcome;
        try {
            outcome = runChildWorkflow(child);
        } catch (ChildWorkflowFailure f) {
            throw new StepFailure(step.id(), "child_failed", "Child job " + child.id() + " (" + child.workflow() + ") failed in the engine: " + describe(f), false);
        }
        if (outcome.status() == JobStatus.CANCELLED) {
            if (cancelled) {
                throw new JobCancelled(ctx.jobId());
            }
            throw new StepFailure(step.id(), "cancelled", "Child job " + child.id() + " (" + child.workflow() + ") was cancelled", false, true);
        }
        if (!outcome.succeeded()) {
            throw new StepFailure(step.id(), outcome.errorClass() == null ? "child_failed" : outcome.errorClass(),
                    "Child job " + child.id() + " (" + child.workflow() + ") " + outcome.status().json()
                            + (outcome.message() == null ? "" : ": " + outcome.message()), false);
        }
        ObjectNode out = F.objectNode();
        out.put("jobId", child.id());
        out.put("status", outcome.status().json());
        out.set("outputs", outcome.output() == null ? F.objectNode() : outcome.output());
        return out;
    }

    /**
     * Run a job as a child workflow (its id the job's) to its end. While it runs, a cancel of
     * this job is forwarded to it once, and the child ends cancelled after its own unwinding. A
     * child cancelled through Temporal itself closes as cancelled rather than returning its
     * outcome (it published its own {@code job.cancelled} first), so that reads as a cancelled
     * outcome too.
     */
    private Outcome runChildWorkflow(Job child) {
        ChildWorkflowOptions options = ChildWorkflowOptions.newBuilder()
                .setWorkflowId(child.id())
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                .build();
        InterpreterWorkflow stub = Workflow.newChildWorkflowStub(InterpreterWorkflow.class, options);
        Promise<Outcome> result = Async.function(stub::run, child);
        Promise<Boolean> started = Workflow.getWorkflowExecution(stub).handle((execution, failure) -> failure == null);
        boolean[] forwarded = {false};
        try {
            while (!result.isCompleted()) {
                Workflow.await(() -> result.isCompleted() || (cancelled && !forwarded[0] && started.isCompleted()));
                if (!result.isCompleted() && cancelled && !forwarded[0]) {
                    forwarded[0] = true;
                    if (started.get()) {
                        forwardCancel(child.id(), stub);
                    }
                }
            }
            return result.get();
        } catch (CanceledFailure e) {
            throw temporalCancel();
        } catch (ChildWorkflowFailure f) {
            if (CancellationScope.current().isCancelRequested()) {
                throw temporalCancel();
            }
            if (f.getCause() instanceof CanceledFailure) {
                return Outcome.cancelled();
            }
            throw f;
        }
    }

    private void forwardCancel(String childId, InterpreterWorkflow child) {
        try {
            child.cancel(cancelledBy);
        } catch (RuntimeException e) {
            // the child may have ended in the meantime: its result says how
            log.warn("could not forward the cancel of {} to child {}: {}", ref.id(), childId, e.toString());
        }
    }

    // ---------------------------------------------------------------- StepExecutor: people

    @Override
    public String requestApproval(JobContext ctx, Step step, ApprovalRequest request) {
        if (job.preApproved()) {
            return Workflow.sideEffect(String.class, () -> Ids.next("apr"));
        }
        return invoke(step, () -> records.requestApproval(ref, step, request, job.preview()));
    }

    @Override
    public Optional<ApprovalDecision> awaitDecision(JobContext ctx, Step step, String approvalId, Duration timeout) {
        if (job.preApproved()) {
            return Optional.of(ApprovalDecision.approved(job.requesterId(), "approved once for the whole batch", now()));
        }
        return awaitApproval(approvalId, timeout);
    }

    private Optional<ApprovalDecision> awaitApproval(String approvalId, Duration timeout) {
        status = JobStatus.WAITING_APPROVAL;
        waitingOn = approvalId;
        try {
            await(timeout, () -> decisions.containsKey(approvalId) || cancelled);
            ApprovalDecision decision = decisions.get(approvalId);
            if (decision == null && cancelled) {
                throw new JobCancelled(ref.id());
            }
            return Optional.ofNullable(decision);
        } finally {
            status = JobStatus.RUNNING;
            waitingOn = null;
        }
    }

    @Override
    public void escalateApproval(JobContext ctx, Step step, String approvalId, String to) {
        invoke(step, () -> {
            events.escalateApproval(ref.id(), step.id(), approvalId, to);
            return null;
        });
    }

    @Override
    public Optional<AttentionDecision> awaitAttention(JobContext ctx, Step step, Attention attention) {
        if (!announced.remove(step.id())) {
            invoke(step, () -> {
                events.publishAttention(ref.id(), attention);
                return null;
            });
        }
        Duration total = Durations.parse(attention.timeout(), Durations.parse(Attention.DEFAULT_TIMEOUT));
        Duration elapsed = attention.since() == null ? Duration.ZERO : Duration.between(attention.since(), now());
        Duration remaining = total.minus(elapsed);
        if (remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        status = JobStatus.NEEDS_ATTENTION;
        waitingOn = step.id();
        AttentionDecision decision;
        try {
            if (cancelled) {
                throw new JobCancelled(ctx.jobId());
            }
            await(remaining, () -> attentionDecision != null || cancelled);
            decision = attentionDecision;
            attentionDecision = null;
            if (decision == null && cancelled) {
                throw new JobCancelled(ctx.jobId());
            }
        } finally {
            status = JobStatus.RUNNING;
            waitingOn = null;
        }
        if (decision != null) {
            AttentionDecision d = decision;
            invoke(step, () -> {
                events.attentionResolved(ref.id(), step.id(), d.action().json(), d.by());
                return null;
            });
        }
        return Optional.ofNullable(decision);
    }

    @Override
    public void sleep(JobContext ctx, Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            return;
        }
        await(duration, () -> cancelled);
        if (cancelled) {
            throw new JobCancelled(ctx.jobId());
        }
    }

    // ---------------------------------------------------------------- StepExecutor: events, clock, branches

    @Override
    public void onStepEvent(JobContext ctx, StepEvent event) {
        if (event.status() == StepStatus.RUNNING) {
            // the interpreter calls the executor right after, on this thread: a team worker's call reads the attempt here
            Attempts a = attempts.get();
            a.step = event.id();
            a.attempt = event.attempt();
        }
        if (event.replayed() && suppressReplayed) {
            return;
        }
        StepEventData data = StepEventData.of(event);
        invoke(event.step(), () -> {
            events.publishStep(ref.id(), data);
            return null;
        });
    }

    /** A {@code job.log} line; one that cannot be published is not worth failing a step for. */
    @Override
    public void log(JobContext ctx, String message) {
        try {
            events.log(ref.id(), message);
        } catch (ActivityFailure | CanceledFailure e) {
            log.warn("job {}: could not publish the log line '{}': {}", ref.id(), message, e.toString());
        }
    }

    @Override
    public Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }

    @Override
    public boolean cancelled(JobContext ctx) {
        return cancelled;
    }

    @Override
    public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
        List<Promise<JsonNode>> promises = new ArrayList<>();
        for (Supplier<JsonNode> branch : branches) {
            promises.add(Async.function(branch::get));
        }
        List<JsonNode> out = new ArrayList<>();
        RuntimeException first = null;
        for (Promise<JsonNode> p : promises) {
            try {
                out.add(p.get());
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

    // ---------------------------------------------------------------- batches

    private Outcome runBatch() {
        WorkflowDefinition def = job.definition();
        Batch b = job.batch();
        if (def == null || b == null) {
            return new Outcome(JobStatus.FAILED, null, "no_definition", null, "The batch carries no definition or rows");
        }
        List<Wave> waves = new ArrayList<>(b.waves());
        waves.sort(Comparator.comparingInt(Wave::index));
        List<Wave> pending = waves.stream().filter(w -> "pending".equals(w.status())).toList();

        // one approval for the whole batch
        if (approvalRequired(def) && !job.preApproved() && job.approvals().stream().noneMatch(a -> a.status() == Approval.Status.APPROVED)) {
            ApprovalPolicy ap = def.policy().approval();
            String timeout = ap.timeout() == null ? Durations.format(WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT) : ap.timeout();
            Approval a = records.batchApproval(ref, ap.group(), "Run " + job.title() + " on " + b.total() + " rows (" + waves.size() + " waves)?",
                    timeout, job.preview());
            Optional<ApprovalDecision> decision;
            try {
                decision = awaitApproval(a.id(), Durations.parse(timeout, WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT));
            } catch (JobCancelled c) {
                skipWaves(pending, b, "cancelled");
                return Outcome.cancelled();
            }
            if (decision.isEmpty()) {
                events.approvalDecided(a, new ApprovalDecision("expired", Identity.SYSTEM.id(), "nobody decided within " + timeout, now()));
                skipWaves(pending, b, "approval expired");
                return new Outcome(JobStatus.FAILED, null, "expired", "batch", "Nobody in " + ap.group() + " decided within " + timeout);
            }
            if (!decision.get().approved()) {
                skipWaves(pending, b, "rejected by " + decision.get().by());
                return new Outcome(JobStatus.FAILED, null, "rejected", "batch", "Rejected by " + decision.get().by()
                        + (decision.get().comment() == null ? "" : ": " + decision.get().comment()));
            }
        }

        RolloutPolicy rollout = b.rollout() == null ? RolloutPolicy.DEFAULT : b.rollout();
        int maxParallel = WaveScheduler.maxParallel(rollout);
        long processed = 0;
        long failed = 0;
        long succeeded = 0;
        long skipped = 0;
        String stoppedReason = null;
        Set<Integer> untouched = new HashSet<>(pending.stream().map(Wave::index).toList());
        for (int i = 0; i < pending.size(); i++) {
            Wave w = pending.get(i);
            if (cancelled) {
                break;
            }
            List<BatchRow> rows = b.rowsOf(w.index()).stream().filter(r -> "pending".equals(r.status())).toList();
            List<Integer> indexes = rows.stream().map(BatchRow::index).toList();
            untouched.remove(w.index());
            events.wave(ref.id(), w.withStatus("running"), null, indexes, now());
            Map<Integer, Outcome> results = runRows(rows, maxParallel);
            long waveFailed = results.values().stream().filter(o -> !o.succeeded() && o.status() != JobStatus.CANCELLED).count();
            long waveSucceeded = results.values().stream().filter(Outcome::succeeded).count();
            long waveSkipped = rows.size() - results.size() + results.values().stream().filter(o -> o.status() == JobStatus.CANCELLED).count();
            processed += results.size();
            failed += waveFailed;
            succeeded += waveSucceeded;
            skipped += waveSkipped;
            if (cancelled) {
                events.wave(ref.id(), w.withStatus("stopped"), "cancelled", indexes, now());
                break;
            }
            events.wave(ref.id(), w.withStatus("done"), null, indexes, now());
            boolean more = i < pending.size() - 1;
            if (more && WaveScheduler.shouldStop(failed, processed, rollout.stopIfFailedPercent())) {
                stoppedReason = "stopped after wave " + w.index() + ": " + failed + " of " + processed + " rows failed (more than "
                        + rollout.stopIfFailedPercent() + "%)";
                List<Wave> rest = pending.subList(i + 1, pending.size());
                skipped += skipWaves(rest, b, stoppedReason);
                rest.forEach(r -> untouched.remove(r.index()));
                events.log(ref.id(), stoppedReason);
                break;
            }
        }
        ObjectNode output = F.objectNode();
        output.put("total", b.total());
        output.put("succeeded", succeeded);
        output.put("failed", failed);
        output.put("skipped", skipped);
        if (cancelled) {
            skipWaves(pending.stream().filter(w -> untouched.contains(w.index())).toList(), b, "cancelled");
            return Outcome.cancelled();
        }
        if (stoppedReason != null) {
            return new Outcome(JobStatus.FAILED, output, "stopped", null, stoppedReason);
        }
        if (failed > 0) {
            return new Outcome(JobStatus.FAILED, output, "rows_failed", null, failed + " of " + b.total() + " rows failed");
        }
        return Outcome.succeeded(output);
    }

    private static boolean approvalRequired(WorkflowDefinition def) {
        return def.policy() != null && def.policy().approval() != null && def.policy().approval().required();
    }

    /** Mark waves skipped; once Temporal itself cancelled the workflow, the final event settles them in the projection instead. */
    private long skipWaves(List<Wave> waves, Batch b, String reason) {
        long rows = 0;
        boolean publish = !CancellationScope.current().isCancelRequested();
        for (Wave w : waves) {
            List<Integer> indexes = b.rowsOf(w.index()).stream().filter(r -> "pending".equals(r.status())).map(BatchRow::index).toList();
            rows += indexes.size();
            if (publish) {
                events.wave(ref.id(), w.withStatus("skipped"), reason, indexes, now());
            }
        }
        return rows;
    }

    /** Run the rows of one wave as child workflows, at most {@code maxParallel} in flight; a row the engine itself fails on is logged and dropped. */
    private Map<Integer, Outcome> runRows(List<BatchRow> rows, int maxParallel) {
        Map<Integer, Outcome> out = new HashMap<>();
        List<Promise<RowResult>> inFlight = new ArrayList<>();
        int limit = Math.max(1, maxParallel);
        for (BatchRow row : rows) {
            while (inFlight.size() >= limit) {
                collect(inFlight, out);
            }
            inFlight.add(Async.function(() -> new RowResult(row.index(), runRow(row))));
        }
        while (!inFlight.isEmpty()) {
            collect(inFlight, out);
        }
        return out;
    }

    private void collect(List<Promise<RowResult>> inFlight, Map<Integer, Outcome> out) {
        Workflow.await(() -> inFlight.stream().anyMatch(Promise::isCompleted));
        Iterator<Promise<RowResult>> it = inFlight.iterator();
        while (it.hasNext()) {
            Promise<RowResult> p = it.next();
            if (!p.isCompleted()) {
                continue;
            }
            it.remove();
            try {
                RowResult r = p.get();
                out.put(r.index(), r.outcome());
            } catch (RuntimeException e) {
                log.error("batch row failed in the engine: {}", e.toString());
            }
        }
    }

    private Outcome runRow(BatchRow row) {
        if (cancelled) {
            return Outcome.cancelled();
        }
        Job child = records.rowJob(ref, row).withDefinition(job.definition());
        try {
            return runChildWorkflow(child);
        } catch (JobCancelled e) {
            return Outcome.cancelled();
        }
    }

    private record RowResult(int index, Outcome outcome) {
    }

    // ---------------------------------------------------------------- activities

    private StepCall call(Step step, Duration timeout, boolean mintsIds) {
        return new StepCall(ref, step, mintsIds ? ctx.nextSeq() : 0, timeout);
    }

    /** The activities of a step's call: the step timeout plus a grace, one attempt (the interpreter retries). */
    private static StepActivities calls(Duration stepTimeout) {
        return stub((stepTimeout == null ? PortStepExecutor.DEFAULT_CALL_TIMEOUT : stepTimeout).plus(CALL_GRACE));
    }

    private static StepActivities stub(Duration startToClose) {
        return Workflow.newActivityStub(StepActivities.class, ActivityOptions.newBuilder()
                .setStartToCloseTimeout(startToClose)
                .setRetryOptions(ONCE)
                .build());
    }

    /** Call an activity and turn its failure back into the classified {@link StepFailure} the interpreter handles. */
    private <T> T invoke(Step step, Supplier<T> activity) {
        try {
            return activity.get();
        } catch (ActivityFailure f) {
            if (f.getCause() instanceof CanceledFailure) {
                throw temporalCancel();
            }
            throw translate(step, f);
        } catch (CanceledFailure e) {
            throw temporalCancel();
        }
    }

    private static StepFailure translate(Step step, ActivityFailure f) {
        Throwable cause = f.getCause();
        if (cause instanceof ApplicationFailure af) {
            if (StepFailureData.TYPE.equals(af.getType()) && af.getDetails().getSize() > 0) {
                return af.getDetails().get(0, StepFailureData.class).toFailure(step.id());
            }
            return new StepFailure(step.id(), "error", af.getOriginalMessage(), true);
        }
        if (cause instanceof TimeoutFailure t && t.getTimeoutType() == TimeoutType.TIMEOUT_TYPE_HEARTBEAT) {
            return new StepFailure(step.id(), WorkerCalls.WORKER_LOST, "Step " + step.id() + ": the platform worker running it stopped heartbeating and is considered lost",
                    true);
        }
        if (cause instanceof TimeoutFailure) {
            return new StepFailure(step.id(), "timeout", "Step " + step.id() + " timed out", true);
        }
        return new StepFailure(step.id(), "error", cause == null ? f.getOriginalMessage() : describe(cause), true);
    }

    private static String describe(Throwable t) {
        Throwable cause = t instanceof ChildWorkflowFailure && t.getCause() != null ? t.getCause() : t;
        if (cause instanceof ApplicationFailure af) {
            return af.getOriginalMessage();
        }
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /** Temporal itself cancelled the workflow: from here on the job unwinds like a cancel signal unwinds it. */
    private JobCancelled temporalCancel() {
        if (!cancelled) {
            cancelled = true;
            cancelledBy = TEMPORAL;
        }
        return new JobCancelled(ref.id());
    }

    /**
     * {@code Workflow.await} that treats a null timeout as forever and a non-positive one as a
     * plain check (a zero timer is not a timer), and a cancel from Temporal as a cancelled job.
     */
    private boolean await(Duration timeout, Supplier<Boolean> condition) {
        try {
            if (timeout == null) {
                Workflow.await(condition);
                return true;
            }
            if (timeout.isNegative() || timeout.isZero()) {
                return condition.get();
            }
            return Workflow.await(timeout, condition);
        } catch (CanceledFailure e) {
            throw temporalCancel();
        }
    }

    /** A missing node (an absent {@code with} field) crosses the activity boundary as null, which the executor reads as absent too. */
    private static JsonNode node(JsonNode n) {
        return n == null || n.isMissingNode() ? null : n;
    }
}
