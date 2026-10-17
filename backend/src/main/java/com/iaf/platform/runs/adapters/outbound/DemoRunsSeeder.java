package com.iaf.platform.runs.adapters.outbound;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.content.ContentFiles;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.application.JobEvents;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.JobProjection;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobCancelled;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.Preview.AffectedResource;
import com.iaf.platform.runs.domain.PreviewRules;
import com.iaf.platform.runs.domain.SampleStepExecutor;
import com.iaf.platform.runs.domain.SampleStepExecutor.ChildRun;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.runs.domain.StepExecutor.ApprovalRequest;
import com.iaf.platform.runs.domain.StepFolding;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.Semver;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.extern.slf4j.Slf4j;

/**
 * Demo history ({@code platform.demo.seed-history}): about sixty finished jobs over the last two
 * weeks across the sample workflows, walked for real on the recorded samples with a virtual
 * clock (so steps, outputs and durations are what the interpreter would produce), one in twelve
 * running into a failure with a realistic error class (some are retried after attention and
 * finish) and a few cancelled; a telemetry sample for every provider call; and three live states: a MongoDB upgrade waiting for approval, a VM
 * restart that needs attention, and a patch batch in its second wave. The live jobs are handed
 * to the execution engine so approving or retrying them continues the real walk. Everything is
 * published as events, so the audit, telemetry and release slices see the same history.
 */
@Slf4j
@Component
public class DemoRunsSeeder {

    public static final long SEED = 20261017L;
    public static final int HISTORY_JOBS = 60;
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final Map<String, Identity> PEOPLE = new LinkedHashMap<>();
    static {
        PEOPLE.put("gonzalo", new Identity("gonzalo", "Gonzalo", "gonzalo@example.com",
                List.of("team-compute", "team-platform", "compute-approvers", "data-approvers"), List.of("ops", "sre", "admin"), true, Identity.Kind.USER));
        PEOPLE.put("ana", new Identity("ana", "Ana", "ana@example.com", List.of("team-data", "data-approvers"), List.of("ops", "dba"), false, Identity.Kind.USER));
        PEOPLE.put("mateo", new Identity("mateo", "Mateo", "mateo@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER));
        PEOPLE.put("junior", new Identity("junior", "Junior engineer", "junior@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER));
    }

    /** How often each sample workflow shows up; unknown workflows get weight 1. */
    private static final Map<String, Integer> WEIGHTS = Map.of(
            "vm-restart", 18, "create-vm", 10, "delete-vm", 5, "patch-cve", 8, "create-database", 6,
            "request-storage", 5, "build-vm-image", 3, "web-environment", 2, "mongodb-upgrade", 3);

    private final PlatformProperties props;
    private final WorkflowQueries workflows;
    private final ProviderQueries providers;
    private final ResourceQueries resources;
    private final JobProjection projection;
    private final JobEvents events;
    private final TelemetryPort telemetry;
    private final WorkflowExecutionPort execution;
    private final Clock clock;

    public DemoRunsSeeder(PlatformProperties props, WorkflowQueries workflows, ProviderQueries providers, ResourceQueries resources,
            JobProjection projection, JobEvents events, TelemetryPort telemetry, WorkflowExecutionPort execution, Clock clock) {
        this.props = props;
        this.workflows = workflows;
        this.providers = providers;
        this.resources = resources;
        this.projection = projection;
        this.events = events;
        this.telemetry = telemetry;
        this.execution = execution;
        this.clock = clock;
    }

    /** What the seed produced, for logs and tests. */
    public record Report(int jobs, int failed, int cancelled, int calls, String waitingApproval, String needsAttention, String runningBatch) {
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onReady() {
        if (props.getDemo() == null || !props.getDemo().isSeedHistory()) {
            return;
        }
        try {
            Report r = seed();
            log.info("demo history seeded: {} jobs ({} failed, {} cancelled), {} provider calls; waiting approval {}, needs attention {}, running batch {}",
                    r.jobs(), r.failed(), r.cancelled(), r.calls(), r.waitingApproval(), r.needsAttention(), r.runningBatch());
        } catch (RuntimeException e) {
            log.warn("demo history could not be seeded: {}", e.toString(), e);
        }
    }

    public Report seed() {
        Catalog catalog = loadCatalog();
        if (catalog.workflows().isEmpty()) {
            log.warn("no published workflows: nothing to seed");
            return new Report(0, 0, 0, 0, null, null, null);
        }
        Random rnd = new Random(SEED);
        Instant now = clock.instant();
        List<Run> runs = new ArrayList<>();
        List<WorkflowDefinition> weighted = new ArrayList<>();
        for (WorkflowDefinition d : catalog.workflows().values()) {
            int w = WEIGHTS.getOrDefault(d.name(), 1);
            for (int i = 0; i < w; i++) {
                weighted.add(d);
            }
        }
        int failedCount = 0;
        int cancelledCount = 0;
        int calls = 0;
        for (int i = 0; i < HISTORY_JOBS; i++) {
            WorkflowDefinition def = weighted.get(rnd.nextInt(weighted.size()));
            Instant startedAt = now.minus(Duration.ofMinutes(45 + rnd.nextInt(14 * 24 * 60 - 60)));
            Identity who = pick(rnd, List.copyOf(PEOPLE.values()));
            String channel = pick(rnd, List.of("catalog", "catalog", "catalog", "rest", "mcp", "trigger", "catalog"));
            boolean fail = i % 12 == 0;              // five of sixty run into a failure
            boolean cancel = !fail && i % 20 == 10;  // three of sixty are cancelled
            runs.add(new Run(def, SeedInputs.inputFor(def, rnd), who, channel, startedAt, fail, cancel));
        }
        runs.sort(Comparator.comparing(Run::startedAt));
        for (Run run : runs) {
            try {
                Seeded s = walk(catalog, run, rnd);
                publish(s);
                calls += s.executor().calls().size();
                if (s.outcome().status() == JobStatus.FAILED || s.outcome().status() == JobStatus.ROLLED_BACK) {
                    failedCount++;
                } else if (s.outcome().status() == JobStatus.CANCELLED) {
                    cancelledCount++;
                }
            } catch (RuntimeException e) {
                log.warn("could not seed a {} job: {}", run.def().name(), e.toString());
            }
        }
        String waiting = seedWaitingApproval(catalog, now, rnd);
        String attention = seedNeedsAttention(catalog, now, rnd);
        String batch = seedRunningBatch(catalog, now, rnd);
        return new Report(runs.size(), failedCount, cancelledCount, calls, waiting, attention, batch);
    }

    // ---------------------------------------------------------------- one finished job

    private record Run(WorkflowDefinition def, JsonNode input, Identity who, String channel, Instant startedAt, boolean fail, boolean cancel) {
    }

    /** A walked job: the record (with its definition), the executor that holds the events and calls, and how it ended. */
    private record Seeded(Job job, SampleStepExecutor executor, Outcome outcome, Instant endedAt, Map<String, Decision> attentionDecisions,
            Map<String, ApprovalDecision> approvalDecisions) {
    }

    private record Decision(AttentionDecision decision, Duration after) {
    }

    private Seeded walk(Catalog catalog, Run run, Random rnd) {
        WorkflowDefinition def = run.def();
        SampleStepExecutor ex = executor(catalog, run.startedAt(), rnd);
        Job job = JobFactory.newJob(def, def.version(), run.input(), run.who(), run.channel(), null, null, preview(catalog, def, run.input()),
                PolicyDecision.allow("mock"), change(def, rnd), run.startedAt());
        Map<String, ApprovalDecision> approvals = new LinkedHashMap<>();
        Map<String, Decision> attentions = new LinkedHashMap<>();
        ex.approver(request -> {
            Duration thinking = Duration.ofMinutes(2 + rnd.nextInt(38));
            ex.sleep(null, thinking);
            Identity approver = approverFor(request, rnd);
            ApprovalDecision d = ApprovalDecision.approved(approver.id(), pick(rnd, Arrays.asList("ok", "approved", "go ahead", "fine for this window", null)), ex.now());
            approvals.put(request.message(), d);
            return Optional.of(d);
        });
        if (run.fail()) {
            Step victim = victim(def, rnd);
            if (victim != null) {
                String[] failure = failureFor(victim, rnd);
                boolean retryable = !"not_found".equals(failure[0]);
                ex.failAt(victim.id(), failure[0], failure[1], retryable, 1);
                boolean retryAfterAttention = retryable && rnd.nextInt(100) < 40;
                ex.attender(a -> {
                    Duration after = Duration.ofMinutes(3 + rnd.nextInt(25));
                    ex.sleep(null, after);
                    AttentionDecision d = retryAfterAttention ? AttentionDecision.retry(run.who().id()) : AttentionDecision.abort(run.who().id());
                    attentions.put(a.step(), new Decision(d, after));
                    return Optional.of(d);
                });
            }
        }
        if (run.cancel()) {
            List<Step> leaves = leaves(def);
            Step at = leaves.isEmpty() ? null : leaves.get(Math.min(leaves.size() - 1, 1 + rnd.nextInt(Math.max(1, leaves.size() - 1))));
            Function<Step, Duration> latency = latencyFor(rnd);
            ex.latency(step -> {
                if (at != null && step.id().equals(at.id())) {
                    throw new JobCancelled(job.id());
                }
                return latency.apply(step);
            });
        }
        Outcome outcome = new WorkflowInterpreter(def, ex, JobContext.of(job)).run();
        return new Seeded(job, ex, outcome, ex.now(), attentions, approvals);
    }

    /** Publish a walked job (and its children) as events, oldest first, and record its provider calls. */
    private void publish(Seeded s) {
        Job job = s.job();
        List<Pending> pending = new ArrayList<>();
        projection.put(job);
        pending.add(new Pending(job.startedAt(), 0, () -> events.started(job, null, job.startedAt())));
        if (job.change() != null) {
            pending.add(new Pending(job.startedAt(), 1, () -> events.changeOpened(job.id(), job.change(), job.requesterId(), job.startedAt())));
        }
        stepEvents(job, s.executor().events(job.id()), s, pending);
        if (s.outcome().status() == JobStatus.CANCELLED) {
            pending.add(new Pending(s.endedAt(), 9, () -> events.cancelled(job.id(), job.requesterId(), s.endedAt())));
        } else {
            Outcome o = s.outcome();
            long duration = Math.max(0, s.endedAt().toEpochMilli() - job.startedAt().toEpochMilli());
            pending.add(new Pending(s.endedAt(), 9, () -> events.finished(job.id(), job.workflow(), job.version(), o.status(), duration, o.errorClass(),
                    o.failedStep(), o.output(), o.message(), s.endedAt())));
            if (job.change() != null) {
                ChangeTicket closed = job.change().withState(o.succeeded() ? "closed" : "closed-unsuccessful");
                pending.add(new Pending(s.endedAt(), 10, () -> events.changeClosed(job.id(), closed, s.endedAt())));
            }
        }
        for (ChildRun c : s.executor().childRuns()) {
            Job child = JobFactory.child(job, c.definition(), c.input(), c.startedAt()).withId(c.jobId());
            projection.put(child);
            pending.add(new Pending(c.startedAt(), 0, () -> events.started(child, null, c.startedAt())));
            stepEvents(child, s.executor().events(c.jobId()), s, pending);
            Outcome o = c.outcome();
            long duration = Math.max(0, c.endedAt().toEpochMilli() - c.startedAt().toEpochMilli());
            if (o.status() == JobStatus.CANCELLED) {
                pending.add(new Pending(c.endedAt(), 9, () -> events.cancelled(child.id(), Identity.SYSTEM.id(), c.endedAt())));
            } else {
                pending.add(new Pending(c.endedAt(), 9, () -> events.finished(child.id(), child.workflow(), child.version(), o.status(), duration,
                        o.errorClass(), o.failedStep(), o.output(), o.message(), c.endedAt())));
            }
        }
        pending.sort(Comparator.comparing(Pending::at).thenComparingInt(Pending::order));
        pending.forEach(p -> p.publish().run());
        for (SampleStepExecutor.Call c : s.executor().calls()) {
            telemetry.record(new CallSample(c.at(), c.provider(), c.action(), c.environment(), c.ok(), c.durationMs(), c.errorClass(),
                    c.jobId(), c.workflow(), c.version()));
        }
    }

    private record Pending(Instant at, int order, Runnable publish) {
    }

    /** The step events of one job, with the approval and attention events they imply woven in. */
    private void stepEvents(Job job, List<StepEvent> stepEvents, Seeded s, List<Pending> pending) {
        Map<String, Approval> open = new LinkedHashMap<>();
        for (StepEvent e : stepEvents) {
            Instant at = e.at();
            if (e.step().type() == StepType.APPROVAL && e.status() == StepStatus.WAITING) {
                ApprovalRequest request = s.executor().approvals().get("apr_" + job.id() + "_" + e.id());
                Approval a = approval(job, e, request);
                open.put(e.id(), a);
                pending.add(new Pending(at, 2, () -> events.step(job.id(), e)));
                pending.add(new Pending(at, 3, () -> events.approvalRequested(a)));
                continue;
            }
            if (e.step().type() == StepType.APPROVAL && e.status() == StepStatus.SUCCEEDED && open.containsKey(e.id())) {
                Approval a = open.remove(e.id());
                ApprovalDecision d = s.approvalDecisions().get(a.message());
                ApprovalDecision decision = d == null ? ApprovalDecision.approved(job.requesterId(), null, at) : d;
                pending.add(new Pending(at, 2, () -> events.approvalDecided(a, decision)));
                pending.add(new Pending(at, 3, () -> events.step(job.id(), e)));
                continue;
            }
            pending.add(new Pending(at, 3, () -> events.step(job.id(), e)));
            if (e.status() == StepStatus.FAILED && s.attentionDecisions().containsKey(e.id()) && isLastFailure(stepEvents, e)) {
                Decision d = s.attentionDecisions().get(e.id());
                Attention attention = new Attention(e.id(), e.error(), Attention.OPTIONS, at, Attention.DEFAULT_TIMEOUT);
                Instant resolvedAt = at.plus(d.after());
                pending.add(new Pending(at, 4, () -> events.attention(job.id(), attention)));
                pending.add(new Pending(resolvedAt, 1, () -> events.attentionResolved(job.id(), e.id(), d.decision().action().json(), d.decision().by(), resolvedAt)));
            }
        }
    }

    private static boolean isLastFailure(List<StepEvent> all, StepEvent failed) {
        int idx = all.indexOf(failed);
        for (int i = idx + 1; i < all.size(); i++) {
            StepEvent n = all.get(i);
            if (n.id().equals(failed.id()) && n.status() == StepStatus.FAILED) {
                return false;
            }
            if (!n.id().equals(failed.id())) {
                return true;
            }
        }
        return true;
    }

    private static Approval approval(Job job, StepEvent waiting, ApprovalRequest request) {
        return Approval.builder()
                .id(Ids.next("apr"))
                .jobId(job.id())
                .workflow(job.workflow())
                .step(waiting.id())
                .group(request == null ? null : request.group())
                .message(request == null ? waiting.name() : request.message())
                .requestedAt(waiting.at())
                .timeout(request == null || request.timeout() == null ? "24h" : request.timeout())
                .status(Approval.Status.PENDING)
                .preview(job.preview())
                .build();
    }

    // ---------------------------------------------------------------- live states

    /** A MongoDB upgrade whose approval is pending: approve it and the walk continues. */
    private String seedWaitingApproval(Catalog catalog, Instant now, Random rnd) {
        WorkflowDefinition def = catalog.workflows().get("mongodb-upgrade");
        if (def == null) {
            return null;
        }
        Instant startedAt = now.minus(Duration.ofMinutes(52));
        SampleStepExecutor ex = executor(catalog, startedAt, rnd);
        ObjectNode input = F.objectNode();
        input.put("clusterId", "db-9");
        input.put("targetVersion", "8.0");
        input.put("maintenanceWindow", "Sat 02:00-04:00 UTC");
        Job job = JobFactory.newJob(def, def.version(), input, PEOPLE.get("ana"), "catalog", null, null, preview(catalog, def, input),
                PolicyDecision.allow("mock"), change(def, rnd), startedAt);
        ex.approver(request -> {
            throw new JobCancelled(job.id());
        });
        new WorkflowInterpreter(def, ex, JobContext.of(job)).run();
        List<StepEvent> stepEvents = ex.events(job.id());
        StepEvent waiting = stepEvents.stream().filter(e -> e.status() == StepStatus.WAITING).reduce((a, b) -> b).orElse(null);
        if (waiting == null) {
            return null;
        }
        Approval a = approval(job, waiting, ex.approvals().get("apr_" + job.id() + "_" + waiting.id()));
        Job live = job.toBuilder()
                .resume(new Job.Resume(waiting.id(), StepFolding.fold(stepEvents)))
                .build();
        projection.put(live);
        events.started(live, null, startedAt);
        if (live.change() != null) {
            events.changeOpened(live.id(), live.change(), live.requesterId(), startedAt);
        }
        for (StepEvent e : stepEvents) {
            events.step(live.id(), e);
            if (e == waiting) {
                events.approvalRequested(a);
            }
        }
        recordCalls(ex);
        Job current = projection.get(live.id()).orElse(live);
        execution.start(current).subscribe(v -> { }, e -> log.warn("could not resume seeded job {}: {}", live.id(), e.toString()));
        return live.id();
    }

    /** A VM restart stuck on "Wait for power on": retry, skip or abort it and the walk continues. */
    private String seedNeedsAttention(Catalog catalog, Instant now, Random rnd) {
        WorkflowDefinition def = catalog.workflows().get("vm-restart");
        if (def == null) {
            return null;
        }
        Step stuck = def.allSteps().stream()
                .filter(s -> "Wait for power on".equals(s.name()) || "validate".equals(s.id()))
                .findFirst().orElse(null);
        if (stuck == null) {
            return null;
        }
        Instant startedAt = now.minus(Duration.ofMinutes(31));
        SampleStepExecutor ex = executor(catalog, startedAt, rnd);
        ObjectNode input = F.objectNode();
        input.put("vmId", "vm-1042");
        input.put("reason", "stuck after kernel update");
        input.put("environment", "prod");
        Job job = JobFactory.newJob(def, def.version(), input, PEOPLE.get("mateo"), "catalog", null, null, preview(catalog, def, input),
                PolicyDecision.allow("mock"), change(def, rnd), startedAt);
        Map<String, ApprovalDecision> approvals = new LinkedHashMap<>();
        ex.approver(request -> {
            ex.sleep(null, Duration.ofMinutes(4));
            ApprovalDecision d = ApprovalDecision.approved("gonzalo", "go", ex.now());
            approvals.put(request.message(), d);
            return Optional.of(d);
        });
        ex.failAt(stuck.id(), "timeout", "VM did not reach poweredOn within 600s", true, 1);
        List<Attention> seen = new ArrayList<>();
        ex.attender(a -> {
            seen.add(a);
            throw new JobCancelled(job.id());
        });
        new WorkflowInterpreter(def, ex, JobContext.of(job)).run();
        if (seen.isEmpty()) {
            return null;
        }
        List<StepEvent> stepEvents = ex.events(job.id());
        Attention attention = seen.get(0);
        Job live = job.toBuilder()
                .resume(new Job.Resume(stuck.id(), StepFolding.fold(stepEvents)))
                .attention(attention)
                .build();
        projection.put(live);
        List<Pending> pending = new ArrayList<>();
        pending.add(new Pending(startedAt, 0, () -> events.started(live, null, startedAt)));
        if (live.change() != null) {
            pending.add(new Pending(startedAt, 1, () -> events.changeOpened(live.id(), live.change(), live.requesterId(), startedAt)));
        }
        Seeded s = new Seeded(live, ex, Outcome.cancelled(), ex.now(), Map.of(), approvals);
        stepEvents(live, stepEvents, s, pending);
        pending.add(new Pending(attention.since(), 4, () -> events.attention(live.id(), attention)));
        pending.sort(Comparator.comparing(Pending::at).thenComparingInt(Pending::order));
        pending.forEach(p -> p.publish().run());
        recordCalls(ex);
        Job current = projection.get(live.id()).orElse(live);
        execution.start(current).subscribe(v -> { }, e -> log.warn("could not resume seeded job {}: {}", live.id(), e.toString()));
        return live.id();
    }

    /** A patch batch over twelve VMs in its second wave: two rows done, one running, nine to go. Projection only. */
    private String seedRunningBatch(Catalog catalog, Instant now, Random rnd) {
        WorkflowDefinition def = catalog.workflows().get("patch-cve");
        if (def == null) {
            return null;
        }
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ObjectNode in = F.objectNode();
            in.put("vmId", "vm-11" + String.format("%02d", i + 1));
            in.put("cve", "CVE-2026-1234");
            in.put("severity", "high");
            rows.add(in);
        }
        var rollout = def.batchPolicy().rollout() == null ? WorkflowDefinition.RolloutPolicy.DEFAULT : def.batchPolicy().rollout();
        List<Wave> waves = WaveScheduler.plan(rows.size(), rollout);
        Instant startedAt = now.minus(Duration.ofMinutes(9));
        ObjectNode defaults = F.objectNode();
        defaults.put("cve", "CVE-2026-1234");
        defaults.put("severity", "high");
        Preview preview = preview(catalog, def, rows.get(0));
        Job batch = JobFactory.newBatch(def, def.version(), rows, defaults, rollout, waves, PEOPLE.get("gonzalo"), "batch", preview,
                PolicyDecision.allow("mock"), change(def, rnd), startedAt);
        projection.put(batch);
        events.started(batch, null, startedAt);
        if (batch.change() != null) {
            events.changeOpened(batch.id(), batch.change(), batch.requesterId(), startedAt);
        }
        Batch b = batch.batch();
        if (waves.size() < 2) {
            return batch.id();
        }
        Wave first = waves.get(0);
        Wave second = waves.get(1);
        List<Integer> firstRows = b.rowsOf(first.index()).stream().map(BatchRow::index).toList();
        List<Integer> secondRows = b.rowsOf(second.index()).stream().map(BatchRow::index).toList();
        Instant wave0 = startedAt.plus(Duration.ofSeconds(20));
        events.wave(batch.id(), first.withStatus("running"), null, firstRows, wave0);
        Instant cursor = wave0;
        for (int idx : firstRows) {
            cursor = seedRow(catalog, batch, b.rows().get(idx), cursor, rnd, false);
        }
        events.wave(batch.id(), first.withStatus("done"), null, firstRows, cursor);
        Instant wave1 = cursor.plus(Duration.ofSeconds(5));
        events.wave(batch.id(), second.withStatus("running"), null, secondRows, wave1);
        for (int i = 0; i < secondRows.size(); i++) {
            boolean running = i == secondRows.size() - 1;
            seedRow(catalog, batch, b.rows().get(secondRows.get(i)), wave1.plus(Duration.ofSeconds(2L * i)), rnd, running);
        }
        return batch.id();
    }

    /** One row of the seeded batch: a finished child, or one still inside its patch step. */
    private Instant seedRow(Catalog catalog, Job batch, BatchRow row, Instant startedAt, Random rnd, boolean leaveRunning) {
        Job child = JobFactory.row(batch, row, startedAt);
        SampleStepExecutor ex = executor(catalog, startedAt, rnd);
        if (leaveRunning) {
            Function<Step, Duration> latency = latencyFor(rnd);
            ex.latency(step -> {
                if (step.type() == StepType.ANSIBLE) {
                    throw new JobCancelled(child.id());
                }
                return latency.apply(step);
            });
        }
        Outcome outcome = new WorkflowInterpreter(child.definition(), ex, JobContext.of(child)).run();
        projection.put(child);
        events.started(child, null, startedAt);
        for (StepEvent e : ex.events(child.id())) {
            events.step(child.id(), e);
        }
        recordCalls(ex);
        Instant endedAt = ex.now();
        if (!leaveRunning) {
            long duration = Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli());
            events.finished(child.id(), child.workflow(), child.version(), outcome.status(), duration, outcome.errorClass(), outcome.failedStep(),
                    outcome.output(), outcome.message(), endedAt);
        }
        return endedAt;
    }

    private void recordCalls(SampleStepExecutor ex) {
        for (SampleStepExecutor.Call c : ex.calls()) {
            telemetry.record(new CallSample(c.at(), c.provider(), c.action(), c.environment(), c.ok(), c.durationMs(), c.errorClass(),
                    c.jobId(), c.workflow(), c.version()));
        }
    }

    // ---------------------------------------------------------------- pieces

    private SampleStepExecutor executor(Catalog catalog, Instant startedAt, Random rnd) {
        SampleStepExecutor ex = new SampleStepExecutor(catalog.providers(), catalog::child, catalog.types(), startedAt);
        ex.latency(latencyFor(rnd));
        return ex;
    }

    /** Realistic durations by step type. */
    static Function<Step, Duration> latencyFor(Random rnd) {
        return step -> switch (step.type()) {
            case PROVIDER -> Duration.ofMillis(200 + rnd.nextInt(2800));
            case ANSIBLE -> Duration.ofSeconds(20 + rnd.nextInt(160));
            case TERRAFORM -> Duration.ofSeconds(30 + rnd.nextInt(210));
            case IDP -> Duration.ofSeconds(5 + rnd.nextInt(25));
            case HTTP -> Duration.ofMillis(100 + rnd.nextInt(900));
            case USE, REGISTER -> Duration.ofMillis(50 + rnd.nextInt(250));
            case SCRIPT, NOTIFY -> Duration.ofMillis(10 + rnd.nextInt(90));
            default -> Duration.ofMillis(5 + rnd.nextInt(20));
        };
    }

    private static List<Step> leaves(WorkflowDefinition def) {
        return def.allSteps().stream().filter(s -> !s.type().isContainer() && s.type() != StepType.APPROVAL && s.type() != StepType.SET
                && s.type() != StepType.RAISE && s.type() != StepType.WAIT).toList();
    }

    private static Step victim(WorkflowDefinition def, Random rnd) {
        List<Step> candidates = def.allSteps().stream()
                .filter(s -> s.type() == StepType.PROVIDER || s.type() == StepType.ANSIBLE || s.type() == StepType.TERRAFORM || s.type() == StepType.IDP
                        || s.type() == StepType.HTTP)
                .toList();
        return candidates.isEmpty() ? null : candidates.get(rnd.nextInt(candidates.size()));
    }

    private static String[] failureFor(Step step, Random rnd) {
        return switch (step.type()) {
            case ANSIBLE -> pick(rnd, List.of(
                    new String[] {"unreachable", "Host unreachable: ssh: connect to host port 22: Connection timed out"},
                    new String[] {"timeout", "Playbook did not finish within the step timeout"}));
            case TERRAFORM -> new String[] {"apply_failed", "Error: timeout while waiting for state to become 'available'"};
            case IDP -> new String[] {"timeout", "Tile request did not complete within the step timeout"};
            default -> pick(rnd, List.of(
                    new String[] {"rate_limited", "429 Too Many Requests: retry after 30s"},
                    new String[] {"not_found", "404: the object does not exist"},
                    new String[] {"timeout", "Upstream did not answer within 30s"},
                    new String[] {"rate_limited", "429 Too Many Requests: quota exceeded for this minute"}));
        };
    }

    private static Identity approverFor(ApprovalRequest request, Random rnd) {
        List<Identity> able = PEOPLE.values().stream()
                .filter(p -> request == null || request.group() == null || p.admin() || p.teams().contains(request.group()))
                .toList();
        return able.isEmpty() ? PEOPLE.get("gonzalo") : pick(rnd, able);
    }

    private static ChangeTicket change(WorkflowDefinition def, Random rnd) {
        if (def.policy() == null || !def.policy().changeRequired()) {
            return null;
        }
        String id = String.format("CHG%07d", 40_000 + rnd.nextInt(1_200));
        return new ChangeTicket(id, MockServiceNowChangeTickets.BASE_URL + id, "implement");
    }

    private static Preview preview(Catalog catalog, WorkflowDefinition def, JsonNode input) {
        if (!def.previewEnabled()) {
            return null;
        }
        try {
            PreviewRules.Catalog c = PreviewRules.catalog(catalog.providers(), catalog::child);
            List<AffectedResource> affected = PreviewRules.resourceRefs(def, input).stream()
                    .map(r -> new AffectedResource(r.id(), r.type(), r.id(), r.via()))
                    .toList();
            return new Preview(def.name(), def.version(), PreviewRules.steps(def, c), affected, PreviewRules.cost(def, c), PolicyDecision.allow("mock"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> T pick(Random rnd, List<T> options) {
        return options.get(rnd.nextInt(options.size()));
    }

    // ---------------------------------------------------------------- catalog

    /** The published workflows, providers and resource types; the content directory when the other slices have nothing yet. */
    private record Catalog(Map<String, WorkflowDefinition> workflows, List<ProviderDefinition> providers, List<ResourceTypeDefinition> types) {
        Optional<WorkflowDefinition> child(String workflow, String range) {
            WorkflowDefinition d = workflows.get(workflow);
            if (d == null) {
                return Optional.empty();
            }
            try {
                return Semver.parse(d.version()).satisfies(range == null || range.isBlank() ? "*" : range) ? Optional.of(d) : Optional.empty();
            } catch (RuntimeException e) {
                return Optional.of(d);
            }
        }
    }

    private Catalog loadCatalog() {
        Map<String, WorkflowDefinition> defs = new LinkedHashMap<>();
        List<ProviderDefinition> provs = new ArrayList<>();
        List<ResourceTypeDefinition> types = new ArrayList<>();
        try {
            List<WorkflowRecord> published = workflows.publishedAll().collectList().block(Duration.ofSeconds(15));
            if (published != null) {
                published.forEach(r -> defs.put(r.name(), r.definition()));
            }
        } catch (RuntimeException e) {
            log.warn("could not read published workflows for the seed: {}", e.toString());
        }
        try {
            List<ProviderDefinition> all = providers.all().collectList().block(Duration.ofSeconds(15));
            if (all != null) {
                provs.addAll(all);
            }
        } catch (RuntimeException e) {
            log.warn("could not read providers for the seed: {}", e.toString());
        }
        try {
            List<ResourceTypeDefinition> all = resources.types().collectList().block(Duration.ofSeconds(15));
            if (all != null) {
                types.addAll(all);
            }
        } catch (RuntimeException e) {
            log.warn("could not read resource types for the seed: {}", e.toString());
        }
        if (defs.isEmpty() || provs.isEmpty() || types.isEmpty()) {
            Path root = Path.of(props.getContent() == null || props.getContent().getDir() == null ? ".." : props.getContent().getDir())
                    .toAbsolutePath().normalize();
            for (ContentFiles.ContentFile f : ContentFiles.scan(root)) {
                try {
                    switch (f.kind()) {
                        case "Workflow" -> {
                            if (defs.isEmpty()) {
                                WorkflowDefinition d = WorkflowYaml.parse(f.yaml());
                                defs.putIfAbsent(d.name(), d);
                            }
                        }
                        case "Provider" -> {
                            if (provs.isEmpty()) {
                                provs.add(ProviderYaml.parse(f.yaml()));
                            }
                        }
                        case "ResourceType" -> {
                            if (types.isEmpty()) {
                                types.add(ResourceTypeDefinition.parse(f.yaml()));
                            }
                        }
                        default -> {
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("skipping {}: {}", f.path(), e.toString());
                }
            }
        }
        return new Catalog(defs, provs, types);
    }
}
