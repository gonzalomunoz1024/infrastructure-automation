package com.iaf.platform.runs.adapters.outbound;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.runs.application.JobEvents;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.application.PortStepExecutor;
import com.iaf.platform.runs.application.StepExecutors;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.ChildFailure;
import com.iaf.platform.runs.domain.ChildResults;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.BatchApprovals;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobCancelled;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuples;

/**
 * The demo engine: every job walks the interpreter on its own {@code boundedElastic} thread,
 * approvals and attention decisions are latches the command handler completes, cancellation is a
 * flag the walker sees at the next step boundary (and a latch that wakes any sleep or wait). A
 * batch runs its rows as child jobs, wave by wave, at most {@code maxParallel} at a time, and
 * stops when the failed share exceeds the policy. Pending states live as long as the process:
 * a job waiting since yesterday is still waiting after lunch.
 */
@Slf4j
@Component
public class InMemoryWorkflowExecution implements WorkflowExecutionPort, PortStepExecutor.Engine {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final StepExecutors executors;
    private final JobProjection projection;
    private final JobEvents events;
    private final ChangeTicketPort changes;
    private final Clock clock;
    private final Scheduler scheduler = Schedulers.boundedElastic();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    /** One running job: its record, the cancel flag and latch, the latches of its waits, and the children it is waiting on. */
    private static final class Execution {
        final Job job;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final CountDownLatch cancelLatch = new CountDownLatch(1);
        final Map<String, CompletableFuture<ApprovalDecision>> approvals = new ConcurrentHashMap<>();
        /** Guards {@link #attention} and {@link #earlyAttention}: a decision and a pause never pass each other by. */
        final Object attentionLock = new Object();
        /** The pause for attention the walker waits in, if any. */
        Pause attention;
        /** A decision that came while no pause was open, for the step it answers; the next pause of that step takes it. */
        EarlyDecision earlyAttention;
        final Set<String> children = ConcurrentHashMap.newKeySet();
        /** use steps waiting for a resource; a cancel ends them */
        final Set<CompletableFuture<?>> resourceWaits = ConcurrentHashMap.newKeySet();
        volatile String cancelledBy;
        /** the interpreter's context, kept so a parent sees what this child registered */
        volatile JobContext context;

        Execution(Job job) {
            this.job = job;
        }
    }

    /** A pause for attention on a step, and the future its decision completes. */
    private record Pause(String step, CompletableFuture<AttentionDecision> decision) {
    }

    /** A decision for {@code step} (null: whichever pause comes first) that arrived before its pause opened. */
    private record EarlyDecision(String step, AttentionDecision decision) {
    }

    public InMemoryWorkflowExecution(StepExecutors executors, JobProjection projection, JobEvents events, ChangeTicketPort changes, Clock clock) {
        this.executors = executors;
        this.projection = projection;
        this.events = events;
        this.changes = changes;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- WorkflowExecutionPort

    @Override
    public Mono<Void> start(Job job) {
        return Mono.fromRunnable(() -> {
            Execution exec = new Execution(job);
            Execution previous = executions.putIfAbsent(job.id(), exec);
            if (previous != null) {
                throw new ConflictException("Job " + job.id() + " is already running");
            }
            scheduler.schedule(() -> {
                try {
                    if (job.isBatch()) {
                        runBatch(exec);
                    } else {
                        runSingle(exec);
                    }
                } catch (RuntimeException e) {
                    log.error("job {} crashed: {}", job.id(), e.toString(), e);
                    finish(exec, new Outcome(JobStatus.FAILED, null, "engine_error", null, e.getMessage()));
                } finally {
                    executions.remove(job.id(), exec);
                }
            });
        });
    }

    @Override
    public Mono<Void> signalApproval(String jobId, String approvalId, ApprovalDecision decision) {
        return Mono.fromRunnable(() -> {
            Execution exec = executions.get(jobId);
            if (exec == null) {
                throw new ConflictException("Job " + jobId + " is not running");
            }
            exec.approvals.computeIfAbsent(approvalId, k -> new CompletableFuture<>()).complete(decision);
        });
    }

    /**
     * The open pause takes the decision; without one (the walker has not reached the pause yet, as
     * a resumed job may not have) it is kept for the step the job reads as paused on, and only a
     * pause of that step takes it later.
     */
    @Override
    public Mono<Void> signalAttention(String jobId, AttentionDecision decision) {
        return Mono.fromRunnable(() -> {
            Execution exec = executions.get(jobId);
            if (exec == null) {
                throw new ConflictException("Job " + jobId + " is not running");
            }
            String step = projection.get(jobId).map(Job::attention).map(Attention::step).orElse(null);
            synchronized (exec.attentionLock) {
                Pause pause = exec.attention;
                if (pause == null || !pause.decision().complete(decision)) {
                    exec.earlyAttention = new EarlyDecision(step, decision);
                }
            }
        });
    }

    @Override
    public Mono<Void> cancel(String jobId, String by) {
        return Mono.fromRunnable(() -> {
            Execution exec = executions.get(jobId);
            if (exec != null) {
                cancel(exec, by);
            }
        });
    }

    private void cancel(Execution exec, String by) {
        if (exec.cancelled.compareAndSet(false, true)) {
            exec.cancelledBy = by;
        }
        exec.cancelLatch.countDown();
        JobCancelled cause = new JobCancelled(exec.job.id());
        exec.approvals.values().forEach(f -> f.completeExceptionally(cause));
        synchronized (exec.attentionLock) {
            if (exec.attention != null) {
                exec.attention.decision().completeExceptionally(cause);
            }
        }
        exec.resourceWaits.forEach(f -> f.completeExceptionally(cause));
        for (String child : exec.children) {
            Execution c = executions.get(child);
            if (c != null) {
                cancel(c, by);
            }
        }
    }

    @Override
    public Mono<Boolean> isRunning(String jobId) {
        return Mono.fromSupplier(() -> executions.containsKey(jobId));
    }

    // ---------------------------------------------------------------- single jobs

    private Outcome runSingle(Execution exec) {
        Job job = exec.job;
        if (job.definition() == null) {
            Outcome o = new Outcome(JobStatus.FAILED, null, "no_definition", null, "The job carries no workflow definition");
            finish(exec, o);
            return o;
        }
        if (exec.cancelled.get()) {
            Outcome o = Outcome.cancelled();
            finish(exec, o);
            return o;
        }
        PortStepExecutor executor = executors.live(job, this);
        WorkflowInterpreter.Resume resume = resumeOf(job);
        exec.context = JobContext.of(job);
        Outcome outcome = new WorkflowInterpreter(job.definition(), executor, exec.context, resume).run();
        finish(exec, outcome);
        return outcome;
    }

    private static WorkflowInterpreter.Resume resumeOf(Job job) {
        if (job.resume() == null || job.resume().fromStep() == null) {
            return null;
        }
        Approval pending = job.pendingApproval(job.resume().fromStep());
        return new WorkflowInterpreter.Resume(job.resume().fromStep(), job.resume().seed(), pending == null ? null : pending.id(), job.attention());
    }

    private void finish(Execution exec, Outcome outcome) {
        Job job = exec.job;
        Instant now = clock.instant();
        long duration = job.startedAt() == null ? 0 : Math.max(0, now.toEpochMilli() - job.startedAt().toEpochMilli());
        try {
            if (outcome.status() == JobStatus.CANCELLED) {
                events.cancelled(job.id(), exec.cancelledBy == null ? Identity.SYSTEM.id() : exec.cancelledBy);
            } else {
                events.finished(job.id(), job.workflow(), job.version(), outcome.status(), duration, outcome.errorClass(), outcome.failedStep(),
                        outcome.output(), outcome.message(), now);
            }
        } catch (RuntimeException e) {
            // the store refused the final event: this engine has no activity to retry it (Temporal's does)
            log.error("job {} ended {} but its final event could not be recorded: {}", job.id(), outcome.status().json(), e.toString());
        }
        if (job.change() != null && job.change().id() != null && !"linked".equals(job.change().state())) {
            changes.close(job.change().id(), outcome.succeeded())
                    .subscribe(c -> events.changeClosed(job.id(), c), e -> log.warn("could not close change {}: {}", job.change().id(), e.toString()));
        }
    }

    // ---------------------------------------------------------------- Engine

    @Override
    public Optional<ApprovalDecision> awaitDecision(JobContext ctx, String approvalId, Duration timeout) {
        Execution exec = executions.get(ctx.jobId());
        if (exec == null) {
            return Optional.empty();
        }
        CompletableFuture<ApprovalDecision> f = exec.approvals.computeIfAbsent(approvalId, k -> new CompletableFuture<>());
        return await(ctx, f, timeout);
    }

    @Override
    public Optional<AttentionDecision> awaitAttention(JobContext ctx, Attention attention, Duration timeout) {
        Execution exec = executions.get(ctx.jobId());
        if (exec == null) {
            return Optional.empty();
        }
        Pause pause = new Pause(attention.step(), new CompletableFuture<>());
        synchronized (exec.attentionLock) {
            EarlyDecision early = exec.earlyAttention;
            exec.earlyAttention = null;
            if (early != null && (early.step() == null || early.step().equals(attention.step()))) {
                pause.decision().complete(early.decision());
            }
            // an early decision for another step answered a pause that is over: it is dropped
            exec.attention = pause;
        }
        try {
            if (exec.cancelled.get() && !pause.decision().isDone()) {
                throw new JobCancelled(ctx.jobId());
            }
            return await(ctx, pause.decision(), timeout);
        } finally {
            synchronized (exec.attentionLock) {
                if (exec.attention == pause) {
                    exec.attention = null;
                }
            }
        }
    }

    private static <T> Optional<T> await(JobContext ctx, CompletableFuture<T> f, Duration timeout) {
        long millis = timeout == null ? Long.MAX_VALUE / 4 : Math.max(0, timeout.toMillis());
        try {
            return Optional.ofNullable(f.get(millis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof JobCancelled c) {
                throw c;
            }
            throw new IllegalStateException(e.getCause() == null ? e : e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobCancelled(ctx.jobId());
        }
    }

    @Override
    public void sleep(JobContext ctx, Duration duration) {
        Execution exec = executions.get(ctx.jobId());
        try {
            if (exec == null) {
                Thread.sleep(duration.toMillis());
                return;
            }
            if (exec.cancelLatch.await(duration.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new JobCancelled(ctx.jobId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobCancelled(ctx.jobId());
        }
    }

    @Override
    public boolean cancelled(JobContext ctx) {
        Execution exec = executions.get(ctx.jobId());
        return exec != null && exec.cancelled.get();
    }

    /** Woken by the registry's events (ResourceQueries.awaitState listens on the EventBus); nothing polls. */
    @Override
    public boolean awaitResource(JobContext ctx, String type, String id, String state, Duration timeout) {
        Execution exec = executions.get(ctx.jobId());
        CompletableFuture<Resource> f = executors.ports().resources().awaitState(type, id, state, timeout).toFuture();
        if (exec != null) {
            exec.resourceWaits.add(f);
        }
        try {
            if (exec != null && exec.cancelled.get()) {
                throw new JobCancelled(ctx.jobId());
            }
            return await(ctx, f, timeout).isPresent();
        } finally {
            if (exec != null) {
                exec.resourceWaits.remove(f);
            }
            f.cancel(true);
        }
    }

    @Override
    public JsonNode runChild(JobContext ctx, Step step, Job childJob, Duration timeout) {
        Execution parent = executions.get(ctx.jobId());
        Execution cexec = new Execution(childJob);
        projection.put(childJob);
        events.started(childJob);
        executions.put(childJob.id(), cexec);
        if (parent != null) {
            parent.children.add(childJob.id());
            if (parent.cancelled.get()) {
                cancel(cexec, parent.cancelledBy);
            }
        }
        ctx.addChild(childJob.id());
        Outcome outcome;
        try {
            outcome = runSingle(cexec);
        } finally {
            executions.remove(childJob.id(), cexec);
            if (parent != null) {
                parent.children.remove(childJob.id());
            }
        }
        if (outcome.status() == JobStatus.CANCELLED && parent != null && parent.cancelled.get()) {
            throw new JobCancelled(ctx.jobId());
        }
        if (!outcome.succeeded()) {
            throw ChildFailure.of(step.id(), childJob.id(), childJob.workflow(), outcome);
        }
        return ChildResults.of(childJob.id(), outcome, cexec.context);
    }

    @Override
    public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (Supplier<JsonNode> b : branches) {
            futures.add(Mono.fromCallable(b::get).subscribeOn(scheduler).toFuture());
        }
        List<JsonNode> out = new ArrayList<>();
        RuntimeException first = null;
        for (CompletableFuture<JsonNode> f : futures) {
            try {
                out.add(f.get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                RuntimeException re = cause instanceof RuntimeException r ? r : new IllegalStateException(cause);
                if (first == null) {
                    first = re;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JobCancelled(ctx.jobId());
            }
        }
        if (first != null) {
            throw first;
        }
        return out;
    }

    // ---------------------------------------------------------------- batches

    private void runBatch(Execution exec) {
        Job batch = exec.job;
        WorkflowDefinition def = batch.definition();
        Batch b = batch.batch();
        if (def == null || b == null) {
            finish(exec, new Outcome(JobStatus.FAILED, null, "no_definition", null, "The batch carries no definition or rows"));
            return;
        }
        JobContext ctx = JobContext.of(batch);
        List<Wave> waves = new ArrayList<>(b.waves());
        waves.sort(Comparator.comparingInt(Wave::index));
        List<Wave> pending = waves.stream().filter(w -> "pending".equals(w.status())).toList();

        // one approval for the whole batch: the policy block asks for it, or the policy engine did for one of its rows
        if (BatchApprovals.required(batch) && !batch.preApproved() && batch.approvals().stream().noneMatch(a -> a.status() == Approval.Status.APPROVED)) {
            String group = BatchApprovals.group(def);
            String timeout = BatchApprovals.timeout(def);
            Approval a = Approval.builder()
                    .id(Ids.next("apr"))
                    .jobId(batch.id())
                    .workflow(batch.workflow())
                    .step("batch")
                    .group(group)
                    .message("Run " + batch.title() + " on " + b.total() + " rows (" + waves.size() + " waves)?")
                    .requestedAt(clock.instant())
                    .timeout(timeout)
                    .status(Approval.Status.PENDING)
                    .preview(batch.preview())
                    .build();
            events.approvalRequested(a);
            Optional<ApprovalDecision> decision;
            try {
                decision = awaitDecision(ctx, a.id(), Durations.parse(timeout, WorkflowInterpreter.DEFAULT_APPROVAL_TIMEOUT));
            } catch (JobCancelled c) {
                skipWaves(batch.id(), pending, b, "cancelled");
                finish(exec, Outcome.cancelled());
                return;
            }
            if (decision.isEmpty()) {
                events.approvalDecided(a, new ApprovalDecision("expired", Identity.SYSTEM.id(), "nobody decided within " + timeout, clock.instant()));
                skipWaves(batch.id(), pending, b, "approval expired");
                finish(exec, new Outcome(JobStatus.FAILED, null, "expired", "batch", "Nobody in " + group + " decided within " + timeout));
                return;
            }
            if (!decision.get().approved()) {
                skipWaves(batch.id(), pending, b, "rejected by " + decision.get().by());
                finish(exec, new Outcome(JobStatus.FAILED, null, "rejected", "batch", "Rejected by " + decision.get().by()
                        + (decision.get().comment() == null ? "" : ": " + decision.get().comment())));
                return;
            }
        }

        RolloutPolicy rollout = b.rollout() == null ? RolloutPolicy.DEFAULT : b.rollout();
        int maxParallel = WaveScheduler.maxParallel(rollout);
        long processed = 0;
        long failed = 0;
        long succeeded = 0;
        long skipped = 0;
        String stoppedReason = null;
        for (int i = 0; i < pending.size(); i++) {
            Wave w = pending.get(i);
            if (exec.cancelled.get()) {
                break;
            }
            List<BatchRow> rows = b.rowsOf(w.index()).stream().filter(r -> "pending".equals(r.status())).toList();
            List<Integer> indexes = rows.stream().map(BatchRow::index).toList();
            events.wave(batch.id(), w.withStatus("running"), null, indexes);
            Map<Integer, Outcome> results = runRows(exec, rows, maxParallel);
            long waveFailed = results.values().stream().filter(o -> !o.succeeded() && o.status() != JobStatus.CANCELLED).count();
            long waveSucceeded = results.values().stream().filter(Outcome::succeeded).count();
            long waveSkipped = rows.size() - results.size() + results.values().stream().filter(o -> o.status() == JobStatus.CANCELLED).count();
            processed += results.size();
            failed += waveFailed;
            succeeded += waveSucceeded;
            skipped += waveSkipped;
            if (exec.cancelled.get()) {
                events.wave(batch.id(), w.withStatus("stopped"), "cancelled", indexes);
                break;
            }
            events.wave(batch.id(), w.withStatus("done"), null, indexes);
            boolean more = i < pending.size() - 1;
            if (more && WaveScheduler.shouldStop(failed, processed, rollout.stopIfFailedPercent())) {
                stoppedReason = "stopped after wave " + w.index() + ": " + failed + " of " + processed + " rows failed (more than "
                        + rollout.stopIfFailedPercent() + "%)";
                List<Wave> rest = pending.subList(i + 1, pending.size());
                skipped += skipWaves(batch.id(), rest, b, stoppedReason);
                events.log(batch.id(), stoppedReason);
                break;
            }
        }
        ObjectNode output = F.objectNode();
        output.put("total", b.total());
        output.put("succeeded", succeeded);
        output.put("failed", failed);
        output.put("skipped", skipped);
        if (exec.cancelled.get()) {
            List<Wave> rest = pending.stream().filter(w -> projectionWaveIsPending(batch.id(), w)).toList();
            skipWaves(batch.id(), rest, b, "cancelled");
            finish(exec, Outcome.cancelled());
            return;
        }
        if (stoppedReason != null) {
            finish(exec, new Outcome(JobStatus.FAILED, output, "stopped", null, stoppedReason));
        } else if (failed > 0) {
            finish(exec, new Outcome(JobStatus.FAILED, output, "rows_failed", null, failed + " of " + b.total() + " rows failed"));
        } else {
            finish(exec, Outcome.succeeded(output));
        }
    }

    private boolean projectionWaveIsPending(String batchId, Wave w) {
        return projection.get(batchId).map(j -> j.batch() == null ? null : j.batch().wave(w.index()))
                .map(x -> "pending".equals(x.status())).orElse(true);
    }


    private long skipWaves(String batchId, List<Wave> waves, Batch b, String reason) {
        long rows = 0;
        for (Wave w : waves) {
            List<Integer> indexes = b.rowsOf(w.index()).stream().filter(r -> "pending".equals(r.status())).map(BatchRow::index).toList();
            rows += indexes.size();
            events.wave(batchId, w.withStatus("skipped"), reason, indexes);
        }
        return rows;
    }

    /** Run the rows of one wave as child jobs, at most {@code maxParallel} at a time. */
    private Map<Integer, Outcome> runRows(Execution exec, List<BatchRow> rows, int maxParallel) {
        Map<Integer, Outcome> out = new ConcurrentHashMap<>();
        Flux.fromIterable(rows)
                .flatMap(row -> Mono.fromCallable(() -> Tuples.of(row.index(), runRow(exec, row))).subscribeOn(scheduler), Math.max(1, maxParallel))
                .doOnNext(t -> out.put(t.getT1(), t.getT2()))
                .onErrorContinue((e, v) -> log.error("batch row failed in the engine: {}", e.toString()))
                .blockLast();
        return out;
    }

    private Outcome runRow(Execution exec, BatchRow row) {
        if (exec.cancelled.get()) {
            return Outcome.cancelled();
        }
        Job child = JobFactory.row(exec.job, row, clock.instant());
        Execution cexec = new Execution(child);
        projection.put(child);
        events.started(child);
        executions.put(child.id(), cexec);
        exec.children.add(child.id());
        try {
            return runSingle(cexec);
        } finally {
            executions.remove(child.id(), cexec);
            exec.children.remove(child.id());
        }
    }
}
