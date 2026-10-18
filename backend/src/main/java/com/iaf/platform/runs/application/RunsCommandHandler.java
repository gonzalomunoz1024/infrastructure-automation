package com.iaf.platform.runs.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.domain.AccessRules;
import com.iaf.platform.releases.ports.inbound.ReleaseQueries;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Batch.BatchRow;
import com.iaf.platform.runs.domain.Batch.Wave;
import com.iaf.platform.runs.domain.BatchApprovals;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.domain.DryRunResult.DryRunStep;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.runs.domain.Inventory;
import com.iaf.platform.runs.domain.InvalidInputException;
import com.iaf.platform.runs.domain.InvalidInputException.RowProblems;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.runs.domain.TriggerMatching;
import com.iaf.platform.runs.domain.TriggerMatching.Match;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.runs.domain.WorkflowInterpreter;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort;
import com.iaf.platform.runs.ports.outbound.ChangeTicketPort.OpenChange;
import com.iaf.platform.runs.ports.outbound.PriceListPort;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.CreateRequests;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The write side of the runs slice. Starting a job: RBAC, input defaults and schema validation,
 * version routing through the releases slice, the definition snapshot, policy, the preview, the
 * change record, {@code job.started}, and the hand-over to the execution engine. The job's own
 * life (steps, approvals, attention, finish) is the engine's; this handler only signals it.
 */
@Slf4j
@Service
@Primary
public class RunsCommandHandler implements RunsCommands {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** How many rows of a batch are judged by the policy engine at once. */
    static final int POLICY_CONCURRENCY = 8;

    private final WorkflowQueries workflows;
    private final com.iaf.platform.providers.ports.inbound.ProviderQueries providers;
    private final ResourceQueries resources;
    private final ReleaseQueries releases;
    private final PolicyPort policy;
    private final ChangeTicketPort changes;
    private final WorkflowExecutionPort execution;
    private final JobProjection projection;
    private final JobEvents events;
    private final StepExecutors executors;
    private final Previews previews;
    private final Clock clock;

    public RunsCommandHandler(WorkflowQueries workflows, com.iaf.platform.providers.ports.inbound.ProviderQueries providers,
            ResourceQueries resources, ReleaseQueries releases, PolicyPort policy, ChangeTicketPort changes, WorkflowExecutionPort execution,
            JobProjection projection, JobEvents events, StepExecutors executors, PriceListPort prices, Clock clock) {
        this.workflows = workflows;
        this.providers = providers;
        this.resources = resources;
        this.releases = releases;
        this.policy = policy;
        this.changes = changes;
        this.execution = execution;
        this.projection = projection;
        this.events = events;
        this.executors = executors;
        this.previews = new Previews(providers, workflows, resources, executors, prices, clock);
        this.clock = clock;
    }

    // ---------------------------------------------------------------- start

    @Override
    public Mono<CommandAccepted> startJob(StartJob cmd) {
        if (cmd == null || cmd.workflow() == null || cmd.workflow().isBlank()) {
            return Mono.error(new IllegalArgumentException("workflow is required"));
        }
        return identity(cmd.requester()).flatMap(who -> resolveVersion(cmd.workflow(), cmd.version()).flatMap(rec -> {
            WorkflowDefinition def = rec.definition();
            AccessRules.requireInvoke(who, rbac(def), def.name());
            JsonNode input = validate(def, cmd.input());
            String channel = cmd.channel() == null ? "rest" : cmd.channel();
            return requireKnownResources(def, List.of(input), false)
                    .then(requireBuildable(def, List.of(input), false))
                    .then(evaluatePolicy(who, def, rec.version(), input, channel, cmd.parentId()))
                    .flatMap(decision -> previewFor(cmd.preview(), def, List.of(input), decision, cmd.parentId() == null, who)
                            .flatMap(preview -> {
                                Job job = JobFactory.newJob(def, rec.version(), input, who, channel, cmd.parentId(), cmd.traceId(),
                                        preview.orElse(null), decision, null, now());
                                return openChange(job, def, cmd.changeId());
                            }))
                    .flatMap(job -> launch(job, null));
        }));
    }

    @Override
    public Mono<CommandAccepted> startBatch(StartBatch cmd) {
        if (cmd == null || cmd.workflow() == null || cmd.workflow().isBlank()) {
            return Mono.error(new IllegalArgumentException("workflow is required"));
        }
        if (cmd.items() == null || cmd.items().isEmpty()) {
            return Mono.error(new IllegalArgumentException("items must not be empty"));
        }
        return identity(cmd.requester()).flatMap(who -> resolveVersion(cmd.workflow(), cmd.version()).flatMap(rec -> {
            WorkflowDefinition def = rec.definition();
            AccessRules.requireInvoke(who, rbac(def), def.name());
            int max = def.batchPolicy().max() <= 0 ? WorkflowDefinition.BatchPolicy.DEFAULT.max() : def.batchPolicy().max();
            if (cmd.items().size() > max) {
                throw new IllegalArgumentException("A batch of " + def.name() + " may have at most " + max + " rows (" + cmd.items().size() + " given)");
            }
            List<JsonNode> rows = new ArrayList<>();
            List<RowProblems> invalid = new ArrayList<>();
            for (int i = 0; i < cmd.items().size(); i++) {
                JsonNode merged = InputDefaults.apply(def.inputSchema(), InputDefaults.merge(cmd.defaults(), cmd.items().get(i)));
                List<Problem> problems = CreateRequests.inputProblems(def, merged);
                if (!problems.isEmpty()) {
                    invalid.add(new RowProblems(i, problems));
                }
                rows.add(merged);
            }
            if (!invalid.isEmpty()) {
                throw InvalidInputException.rows(def.name(), invalid);
            }
            RolloutPolicy rollout = rollout(cmd.rollout(), def);
            List<Wave> waves = WaveScheduler.plan(rows.size(), rollout);
            String channel = cmd.channel() == null ? "batch" : cmd.channel();
            return requireKnownResources(def, rows, true)
                    .then(requireBuildable(def, rows, true))
                    .then(evaluateBatchPolicy(who, def, rec.version(), rows, channel))
                    .flatMap(decision -> previewFor(cmd.preview(), def, rows, decision, true, who)
                            .flatMap(preview -> {
                                Job job = JobFactory.newBatch(def, rec.version(), rows, cmd.defaults(), rollout, waves, who, channel,
                                        preview.orElse(null), decision, null, cmd.traceId(), now());
                                return openChange(job, def, null);
                            }))
                    .flatMap(job -> launch(job, null))
                    .map(acc -> {
                        Map<String, String> links = new LinkedHashMap<>(acc.links() == null ? Map.of() : acc.links());
                        links.put("rows", String.valueOf(rows.size()));
                        return CommandAccepted.of(acc.id(), acc.status(), links);
                    });
        }));
    }

    private Mono<CommandAccepted> launch(Job job, String rerunOf) {
        return Mono.fromRunnable(() -> {
            projection.put(job);
            events.started(job, rerunOf);
            if (job.change() != null) {
                events.changeOpened(job.id(), job.change(), job.requesterId());
            }
        }).then(execution.start(job)).thenReturn(accepted(job.id(), JobStatus.QUEUED.json(), job.id()));
    }

    // ---------------------------------------------------------------- steer

    @Override
    public Mono<CommandAccepted> cancel(String jobId) {
        return CurrentIdentity.current().flatMap(who -> {
            Job job = job(jobId);
            requireControl(who, job);
            if (job.status() != null && job.status().terminal()) {
                throw new ConflictException("Job " + jobId + " already ended (" + job.status().json() + ")");
            }
            return execution.isRunning(jobId)
                    .flatMap(running -> running ? execution.cancel(jobId, who.id()) : Mono.fromRunnable(() -> events.cancelled(jobId, who.id())))
                    .thenReturn(accepted(jobId, JobStatus.CANCELLED.json(), jobId));
        });
    }

    @Override
    public Mono<CommandAccepted> retry(String jobId, String step, JsonNode input) {
        return CurrentIdentity.current().flatMap(who -> {
            Job job = needsAttention(who, jobId, step);
            AttentionDecision decision = input == null || input.isNull() || input.isMissingNode()
                    ? AttentionDecision.retry(who.id())
                    : AttentionDecision.editInput(input, who.id());
            return signalAttention(job, decision);
        });
    }

    @Override
    public Mono<CommandAccepted> skip(String jobId, String step) {
        return CurrentIdentity.current().flatMap(who -> signalAttention(needsAttention(who, jobId, step), AttentionDecision.skip(who.id())));
    }

    @Override
    public Mono<CommandAccepted> abort(String jobId) {
        return CurrentIdentity.current().flatMap(who -> signalAttention(needsAttention(who, jobId, null), AttentionDecision.abort(who.id())));
    }

    /**
     * Who may steer a job (cancel it, retry a batch's failed rows, or retry, skip and abort a step
     * waiting for attention): its requester, a member of the workflow's owner team, anyone the
     * workflow's {@code policy.rbac} or approver groups name, and admins. Anyone else is refused
     * with the job's id.
     */
    static boolean canControl(Identity who, Job job) {
        if (who.admin() || (who.id() != null && who.id().equals(job.requesterId()))) {
            return true;
        }
        WorkflowDefinition def = job.definition();
        if (def == null) {
            return false;
        }
        String owner = def.metadata() == null ? null : def.metadata().owner();
        if (owner != null && who.teams().contains(owner)) {
            return true;
        }
        List<String> rbac = rbac(def);
        if (rbac != null && !rbac.isEmpty() && AccessRules.canInvoke(who, rbac)) {
            return true;
        }
        return approverGroups(def).stream().anyMatch(g -> AccessRules.canApprove(who, g));
    }

    static void requireControl(Identity who, Job job) {
        if (!canControl(who, job)) {
            throw new ForbiddenException(who.name() + " may not control job " + job.id() + " (" + job.workflow()
                    + " started by " + job.requesterId() + ")");
        }
    }

    /** The groups the workflow's approval policy and its approval steps name. */
    private static List<String> approverGroups(WorkflowDefinition def) {
        List<String> groups = new ArrayList<>();
        if (def.policy() != null && def.policy().approval() != null && def.policy().approval().group() != null) {
            groups.add(def.policy().approval().group());
        }
        for (Step s : def.allSteps()) {
            String group = s.type() == StepType.APPROVAL ? s.withText("group") : null;
            if (group != null && !group.isBlank()) {
                groups.add(group);
            }
        }
        return groups;
    }

    private Job needsAttention(Identity who, String jobId, String step) {
        Job job = job(jobId);
        requireControl(who, job);
        if (job.status() != JobStatus.NEEDS_ATTENTION || job.attention() == null) {
            throw new ConflictException("Job " + jobId + " is not waiting for attention (" + (job.status() == null ? "unknown" : job.status().json()) + ")");
        }
        if (step != null && !step.isBlank() && !step.equals(job.attention().step())) {
            throw new IllegalArgumentException("Job " + jobId + " is paused on step " + job.attention().step() + ", not " + step);
        }
        return job;
    }

    private Mono<CommandAccepted> signalAttention(Job job, AttentionDecision decision) {
        return execution.isRunning(job.id()).flatMap(running -> {
            if (!running) {
                return Mono.error(new ConflictException("Job " + job.id() + " is not held by the execution engine; rerun it from step "
                        + job.attention().step() + " instead"));
            }
            return execution.signalAttention(job.id(), decision);
        }).thenReturn(accepted(job.id(), decision.action() == AttentionDecision.Action.ABORT ? "aborting" : JobStatus.RUNNING.json(), job.id()));
    }

    @Override
    public Mono<CommandAccepted> rerun(String jobId, String fromStep, JsonNode input) {
        return CurrentIdentity.current().flatMap(who -> {
            Job source = job(jobId);
            if (source.definition() == null) {
                throw new ConflictException("Job " + jobId + " has no definition snapshot to rerun");
            }
            if (source.isBatch()) {
                throw new ConflictException("Job " + jobId + " is a batch; retry its failed rows instead");
            }
            if (source.status() == null || source.status().active()) {
                throw new ConflictException("Job " + jobId + " is still " + (source.status() == null ? "running" : source.status().json()));
            }
            WorkflowDefinition def = source.definition();
            List<String> topLevel = def.steps().stream().map(Step::id).toList();
            String from = fromStep == null || fromStep.isBlank() ? (topLevel.isEmpty() ? null : topLevel.get(0)) : fromStep;
            if (from == null || !topLevel.contains(from)) {
                throw new IllegalArgumentException("fromStep must be a top-level step of " + def.name() + ": one of " + topLevel);
            }
            List<String> reached = source.steps().stream().map(JobStep::id).filter(topLevel::contains).toList();
            if (!reached.isEmpty() && !reached.contains(from)) {
                throw new IllegalArgumentException("fromStep must be a step job " + jobId + " reached: one of " + reached);
            }
            AccessRules.requireInvoke(who, rbac(def), def.name());
            JsonNode merged = input == null || input.isNull() ? source.input() : WorkflowInterpreter.merge(source.input(), input);
            JsonNode validated = validate(def, merged);
            // the merged input is checked as a start checks it; the source's preview holds only while the input is the same
            boolean sameInput = validated.equals(source.input());
            return requireKnownResources(def, List.of(validated), false)
                    .then(requireBuildable(def, List.of(validated), false))
                    .then(evaluatePolicy(who, def, source.version(), validated, "rest", null))
                    .flatMap(decision -> (sameInput ? Mono.just(Optional.ofNullable(source.preview())) : previewFor(null, def, List.of(validated), decision, true, who))
                            .flatMap(preview -> openChange(JobFactory.rerun(source, from, validated, preview.orElse(null), who, "rest", null, now())
                                    .withPolicy(decision), def, null)))
                    .flatMap(job -> launch(job, source.id()));
        });
    }

    @Override
    public Mono<CommandAccepted> retryFailedRows(String batchJobId) {
        return CurrentIdentity.current().flatMap(who -> {
            Job job = job(batchJobId);
            requireControl(who, job);
            if (!job.isBatch() || job.batch() == null) {
                throw new ConflictException("Job " + batchJobId + " is not a batch");
            }
            if (job.status() == null || job.status().active()) {
                throw new ConflictException("Batch " + batchJobId + " is still " + (job.status() == null ? "running" : job.status().json()));
            }
            Batch batch = job.batch();
            Set<String> retryable = Set.of("failed", "cancelled", "skipped");
            List<Integer> indexes = batch.rows().stream().filter(r -> retryable.contains(r.status())).map(BatchRow::index).toList();
            if (indexes.isEmpty()) {
                throw new ConflictException("Batch " + batchJobId + " has no failed rows");
            }
            int waveIndex = batch.waves().stream().mapToInt(Wave::index).max().orElse(-1) + 1;
            Wave wave = new Wave(waveIndex, 100, indexes.size(), "pending", null, null);
            List<Wave> waves = new ArrayList<>(batch.waves());
            waves.add(wave);
            List<BatchRow> rows = batch.rows().stream()
                    .map(r -> indexes.contains(r.index()) ? r.withStatus("pending").withWave(waveIndex).withJobId(null).withError(null) : r)
                    .toList();
            Batch next = batch.withRows(rows).withWaves(waves).withStoppedReason(null);
            Job updated = projection.update(job.id(), j -> j.toBuilder()
                    .batch(next)
                    .progress(JobProjection.progressOf(next))
                    .status(JobStatus.QUEUED)
                    .endedAt(null)
                    .durationMs(null)
                    .errorClass(null)
                    .failedStep(null)
                    .output(null)
                    .build()).orElseThrow(() -> new NotFoundException("job", batchJobId));
            events.wave(job.id(), wave, null, indexes);
            events.log(job.id(), who.name() + " retries " + indexes.size() + " failed row" + (indexes.size() == 1 ? "" : "s") + " in wave " + waveIndex);
            return execution.start(updated).thenReturn(accepted(job.id(), JobStatus.QUEUED.json(), job.id()));
        });
    }

    // ---------------------------------------------------------------- approvals

    @Override
    public Mono<CommandAccepted> approve(String approvalId, String comment) {
        return decide(approvalId, comment, true);
    }

    @Override
    public Mono<CommandAccepted> reject(String approvalId, String comment) {
        return decide(approvalId, comment, false);
    }

    /**
     * The engine takes the decision first and only then is it published, so a job the engine
     * does not hold answers 409 and its approval stays pending for a later decision, instead of
     * reading as decided while the job waits for good.
     */
    private Mono<CommandAccepted> decide(String approvalId, String comment, boolean approved) {
        return CurrentIdentity.current().flatMap(who -> {
            Approval a = projection.approval(approvalId).orElseThrow(() -> new NotFoundException("approval", approvalId));
            if (!a.pending()) {
                throw new ConflictException("Approval " + approvalId + " was already " + a.status().json());
            }
            if (a.approverGroups().stream().noneMatch(g -> AccessRules.canApprove(who, g))) {
                AccessRules.requireApprove(who, a.group(), a.workflow());   // escalated: the escalation group may decide too
            }
            ApprovalDecision d = approved ? ApprovalDecision.approved(who.id(), comment, now()) : ApprovalDecision.rejected(who.id(), comment, now());
            return execution.signalApproval(a.jobId(), a.id(), d)
                    .then(Mono.fromRunnable(() -> events.approvalDecided(a, d)))
                    .thenReturn(accepted(a.id(), d.decision(), a.jobId()));
        });
    }

    // ---------------------------------------------------------------- dry run and preview

    @Override
    public Mono<DryRunResult> dryRun(WorkflowDefinition definition, JsonNode input, Identity who) {
        if (definition == null) {
            return Mono.error(new IllegalArgumentException("definition is required"));
        }
        return identity(who).flatMap(w -> Mono.fromCallable(() -> {
            JsonNode in = InputDefaults.apply(definition.inputSchema(), input);
            Job job = JobFactory.newJob(definition, definition.version(), in, w, "dry-run", null, null, null, null, null, now())
                    .withId(Ids.next("dry"));
            List<RecordedStep> recorded = java.util.Collections.synchronizedList(new ArrayList<>());
            PortStepExecutor executor = executors.dryRun(job, (prefix, e) -> recorded.add(new RecordedStep(prefix + e.id(), e)));
            Outcome outcome = new WorkflowInterpreter(definition, executor, JobContext.of(job)).run();
            return dryRunResult(outcome, recorded);
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    private record RecordedStep(String key, StepEvent event) {
    }

    static DryRunResult dryRunResult(Outcome outcome, List<RecordedStep> recorded) {
        Map<String, DryRunStep> steps = new LinkedHashMap<>();
        for (RecordedStep r : recorded) {
            StepEvent e = r.event();
            DryRunStep prev = steps.get(r.key());
            JsonNode input = e.input() != null ? e.input() : prev == null ? null : prev.input();
            JsonNode output = e.status() == StepStatus.SUCCEEDED ? e.output() : prev == null ? null : prev.output();
            String error = e.error() != null ? e.error().errorClass() + ": " + e.error().message() : e.status() == StepStatus.SUCCEEDED ? null : prev == null ? null : prev.error();
            steps.put(r.key(), new DryRunStep(r.key(), e.name(), e.status().json(), input, output, error));
        }
        List<String> path = new ArrayList<>(steps.keySet());
        String error = outcome.succeeded() ? null
                : (outcome.failedStep() == null ? "" : outcome.failedStep() + ": ") + (outcome.message() == null ? outcome.status().json() : outcome.message());
        return new DryRunResult(outcome.succeeded(), path, new ArrayList<>(steps.values()), error);
    }

    @Override
    public Mono<Preview> preview(WorkflowDefinition definition, JsonNode input, Identity who) {
        if (definition == null) {
            return Mono.error(new IllegalArgumentException("definition is required"));
        }
        return identity(who).flatMap(w -> {
            JsonNode in = InputDefaults.apply(definition.inputSchema(), input);
            // judged like a run: an id the registry does not know is a 400, not a plan for nothing
            return requireKnownResources(definition, List.of(in), false)
                    .then(evaluatePolicy(w, definition, definition.version(), in, "preview", null))
                    .flatMap(decision -> buildPreview(definition, List.of(in), decision, w));
        });
    }

    // ---------------------------------------------------------------- triggers

    @Override
    public Mono<TriggerResult> triggerAlert(JsonNode alert) {
        if (alert == null || !alert.isObject()) {
            return Mono.error(new IllegalArgumentException("an alert payload { source, labels, annotations } is required"));
        }
        return published().flatMap(defs -> startMatches(TriggerMatching.alerts(defs, alert)));
    }

    @Override
    public Mono<TriggerResult> triggerEvent(String topic, JsonNode payload) {
        if (topic == null || topic.isBlank()) {
            return Mono.error(new IllegalArgumentException("topic is required"));
        }
        return published().flatMap(defs -> startMatches(TriggerMatching.events(defs, topic, payload == null ? F.objectNode() : payload)));
    }

    private Mono<TriggerResult> startMatches(List<Match> matches) {
        return CurrentIdentity.current().flatMap(who -> {
            List<TriggerStarted> started = java.util.Collections.synchronizedList(new ArrayList<>());
            List<TriggerSkipped> skipped = java.util.Collections.synchronizedList(new ArrayList<>());
            return Flux.fromIterable(matches)
                    .concatMap(m -> startJob(new StartJob(m.workflow().name(), null, m.input(), who, "trigger", null, null, null, null))
                            .doOnNext(acc -> started.add(new TriggerStarted(m.workflow().name(), acc.id())))
                            .onErrorResume(e -> {
                                if (e instanceof ForbiddenException || e instanceof IllegalArgumentException || e instanceof NotFoundException) {
                                    skipped.add(new TriggerSkipped(m.workflow().name(), e.getMessage()));
                                    return Mono.empty();
                                }
                                return Mono.error(e);
                            }))
                    .then(Mono.fromSupplier(() -> new TriggerResult(List.copyOf(started), List.copyOf(skipped))));
        });
    }

    private Mono<List<WorkflowDefinition>> published() {
        return workflows.publishedAll().map(WorkflowRecord::definition).collectList();
    }

    // ---------------------------------------------------------------- pieces

    private Mono<Identity> identity(Identity given) {
        return given != null ? Mono.just(given) : CurrentIdentity.current();
    }

    private Instant now() {
        return clock.instant();
    }

    private Job job(String id) {
        return projection.get(id).orElseThrow(() -> new NotFoundException("job", id));
    }

    private static List<String> rbac(WorkflowDefinition def) {
        return def.policy() == null ? null : def.policy().rbac();
    }

    private static CommandAccepted accepted(String id, String status, String jobId) {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("job", "/api/v1/jobs/" + jobId);
        return CommandAccepted.of(id, status, links);
    }

    /** The version a new job runs: the one asked for, or the published one as the release router routes it. */
    Mono<WorkflowRecord> resolveVersion(String workflow, String version) {
        if (version != null && !version.isBlank()) {
            return workflows.version(workflow, version)
                    .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow version", workflow + "@" + version)));
        }
        return workflows.published(workflow)
                .switchIfEmpty(Mono.error(() -> new NotFoundException("workflow", workflow)))
                .flatMap(pub -> releases.route(workflow, pub.version())
                        .defaultIfEmpty(pub.version())
                        .onErrorResume(e -> {
                            log.warn("release routing failed for {}: {}", workflow, e.toString());
                            return Mono.just(pub.version());
                        })
                        .flatMap(v -> v == null || v.equals(pub.version()) ? Mono.just(pub) : workflows.version(workflow, v).defaultIfEmpty(pub)));
    }

    /** The inventory check: an error naming every row whose x-resource ids the registry does not know. */
    private Mono<Void> requireKnownResources(WorkflowDefinition def, List<JsonNode> rows, boolean batch) {
        List<Inventory.Field> fields = Inventory.fields(def);
        if (fields.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(Inventory.ids(fields, rows))
                .flatMap(id -> resources.get(id))
                .collectMap(Resource::id)
                .flatMap(found -> {
                    List<RowProblems> invalid = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        List<Problem> problems = Inventory.problems(fields, rows.get(i), found);
                        if (!problems.isEmpty()) {
                            invalid.add(new RowProblems(i, problems));
                        }
                    }
                    if (invalid.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    return Mono.error(batch ? InvalidInputException.rows(def.name(), invalid)
                            : new InvalidInputException(def.name(), invalid.get(0).problems()));
                });
    }

    /**
     * Each request to build a resource first ({@code { create: {...} }} on an {@code orCreateWith}
     * input) against the published input of the workflow that builds it: a 400 naming every
     * problem under {@code <input>.create}.
     */
    private Mono<Void> requireBuildable(WorkflowDefinition def, List<JsonNode> rows, boolean batch) {
        Set<String> producers = new TreeSet<>();
        rows.forEach(row -> CreateRequests.of(def, row).forEach(r -> producers.add(r.orCreateWith())));
        if (producers.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(producers)
                .flatMap(name -> workflows.published(name))
                .collectMap(r -> r.definition().name(), WorkflowRecord::definition)
                .flatMap(found -> {
                    List<RowProblems> invalid = new ArrayList<>();
                    for (int i = 0; i < rows.size(); i++) {
                        List<Problem> problems = CreateRequests.problems(def, rows.get(i), name -> Optional.ofNullable(found.get(name)));
                        if (!problems.isEmpty()) {
                            invalid.add(new RowProblems(i, problems));
                        }
                    }
                    if (invalid.isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    return Mono.error(batch ? InvalidInputException.rows(def.name(), invalid)
                            : new InvalidInputException(def.name(), invalid.get(0).problems()));
                });
    }

    /** Defaults filled in, then the schema, with requests to build a resource first let through (judged by requireBuildable). */
    static JsonNode validate(WorkflowDefinition def, JsonNode input) {
        JsonNode in = InputDefaults.apply(def.inputSchema(), input);
        List<Problem> problems = CreateRequests.inputProblems(def, in);
        if (!problems.isEmpty()) {
            throw new InvalidInputException(def.name(), problems);
        }
        return in;
    }

    /**
     * A batch is judged row by row: every row carries its own input and environment, so the
     * engine sees each one. A deny of any row refuses the whole batch, naming the rows; the
     * batch keeps the strictest decision, which asks for the batch approval when any row needs
     * one ({@link BatchApprovals}).
     */
    private Mono<PolicyDecision> evaluateBatchPolicy(Identity who, WorkflowDefinition def, String version, List<JsonNode> rows, String channel) {
        return Flux.range(0, rows.size())
                .flatMapSequential(i -> judge(who, def, version, rows.get(i), channel, null).map(d -> Map.entry(i, d)), POLICY_CONCURRENCY)
                .collectList()
                .map(judged -> {
                    List<Integer> denied = new ArrayList<>();
                    List<String> why = new ArrayList<>();
                    for (Map.Entry<Integer, PolicyDecision> e : judged) {
                        if (InvokePolicy.denied(e.getValue())) {
                            denied.add(e.getKey());
                            denyMessages(e.getValue()).forEach(m -> {
                                if (!why.contains(m)) {
                                    why.add(m);
                                }
                            });
                        }
                    }
                    if (!denied.isEmpty()) {
                        throw new ForbiddenException("Policy denies running " + def.name() + " for row" + (denied.size() == 1 ? " " : "s ") + denied
                                + (why.isEmpty() ? "" : ": " + String.join("; ", why)));
                    }
                    return BatchApprovals.strictest(judged.stream().map(Map.Entry::getValue).toList(), policy.engine());
                });
    }

    private static List<String> denyMessages(PolicyDecision decision) {
        return InvokePolicy.denials(decision);
    }

    private Mono<PolicyDecision> evaluatePolicy(Identity who, WorkflowDefinition def, String version, JsonNode input, String channel, String parentId) {
        return judge(who, def, version, input, channel, parentId).map(decision -> {
            if (InvokePolicy.denied(decision)) {
                throw new ForbiddenException(InvokePolicy.refusal(def.name(), decision));
            }
            return decision;
        });
    }

    /** The engine's answer for one input ({@link InvokePolicy}), an allow when it has none; a deny is the caller's to refuse. */
    private Mono<PolicyDecision> judge(Identity who, WorkflowDefinition def, String version, JsonNode input, String channel, String parentId) {
        return policy.evaluate(InvokePolicy.input(who, def, version, input, channel, parentId))
                .defaultIfEmpty(PolicyDecision.allow(policy.engine()));
    }

    private Mono<Optional<Preview>> previewFor(Preview given, WorkflowDefinition def, List<JsonNode> inputs, PolicyDecision decision, boolean topLevel,
            Identity who) {
        if (given != null) {
            return Mono.just(Optional.of(given));
        }
        if (!topLevel || !def.previewEnabled()) {
            return Mono.just(Optional.empty());
        }
        return buildPreview(def, inputs, decision, who).map(Optional::of)
                .onErrorResume(e -> {
                    log.warn("preview of {} failed: {}", def.name(), e.toString());
                    return Mono.just(Optional.empty());
                });
    }

    /** The preview of a run: the flow judged by a dry run on the first row, the plans, the registry and the price lists ({@link Previews}). */
    Mono<Preview> buildPreview(WorkflowDefinition def, List<JsonNode> inputs, PolicyDecision decision, Identity who) {
        return previews.build(def, inputs, decision, who);
    }

    private Mono<Job> openChange(Job job, WorkflowDefinition def, String changeId) {
        if (changeId != null && !changeId.isBlank()) {
            return Mono.just(job.withChange(new ChangeTicket(changeId, null, "linked")));
        }
        boolean required = def.policy() != null && def.policy().changeRequired();
        if (!required || job.parentId() != null) {
            return Mono.just(job);
        }
        String summary = job.title() + (job.isBatch() ? " on " + job.batch().total() + " rows" : "") + " requested by " + job.requesterId();
        return changes.open(new OpenChange(job.id(), job.workflow(), job.version(), job.requesterId(), summary, job.preview()))
                .map(job::withChange)
                .defaultIfEmpty(job);
    }

    private static RolloutPolicy rollout(RolloutPolicy given, WorkflowDefinition def) {
        RolloutPolicy base = given != null ? given : def.batchPolicy().rollout() != null ? def.batchPolicy().rollout() : RolloutPolicy.DEFAULT;
        List<Integer> waves = base.waves() == null || base.waves().isEmpty() ? RolloutPolicy.DEFAULT.waves() : base.waves();
        int maxParallel = base.maxParallel() < 1 ? RolloutPolicy.DEFAULT.maxParallel() : base.maxParallel();
        int stop = base.stopIfFailedPercent() < 0 ? RolloutPolicy.DEFAULT.stopIfFailedPercent() : base.stopIfFailedPercent();
        return new RolloutPolicy(waves, maxParallel, stop);
    }
}
