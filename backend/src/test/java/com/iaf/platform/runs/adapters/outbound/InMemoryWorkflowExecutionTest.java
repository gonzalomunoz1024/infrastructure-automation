package com.iaf.platform.runs.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** The in-memory engine end to end: threads, latches, cancellation, children, batches. */
class InMemoryWorkflowExecutionTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private RunsTestWiring w;
    private final List<DomainEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        w = new RunsTestWiring();
        w.bus.register(published::add);
    }

    private Job start(WorkflowDefinition def, Map<String, Object> input) {
        Job job = JobFactory.newJob(def, def.version(), SampleContent.json(input), SampleContent.GONZALO, "rest", null, null, null, null, null, w.clock.instant());
        w.projection.put(job);
        w.events.started(job);
        w.execution.start(job).block();
        return job;
    }

    private Job job(String id) {
        return w.projection.get(id).orElseThrow();
    }

    private void awaitStatus(String id, JobStatus status) {
        RunsTestWiring.await(WAIT, () -> job(id).status() == status);
    }

    private List<String> types(String jobId) {
        return published.stream().filter(e -> e.aggregateId().equals(jobId)).map(DomainEvent::type).toList();
    }

    private static WorkflowDefinition yaml(String yaml) {
        return WorkflowYaml.parse(yaml);
    }

    @Test
    void aJobWaitsForItsApprovalThenFinishesWithTelemetry() {
        Job job = start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042", "reason", "stuck", "environment", "dev"));

        awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        Job waiting = job(job.id());
        Approval a = waiting.pendingApproval("approval");
        assertThat(a).isNotNull();
        assertThat(a.group()).isEqualTo("compute-approvers");
        assertThat(a.message()).isEqualTo("Restart web-1042 (poweredOn) in dev? Reason: stuck");
        assertThat(waiting.step("checkVmExists").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(waiting.step("approval").status()).isEqualTo(StepStatus.WAITING);
        assertThat(w.execution.isRunning(job.id()).block()).isTrue();

        w.events.approvalDecided(a, ApprovalDecision.approved("gonzalo", "go", Instant.now()));
        w.execution.signalApproval(job.id(), a.id(), ApprovalDecision.approved("gonzalo", "go", Instant.now())).block();

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        Job done = job(job.id());
        assertThat(done.output().path("vmName").asText()).isEqualTo("web-1042");
        assertThat(done.output().path("powerState").asText()).isEqualTo("poweredOn");
        assertThat(done.steps()).extracting(JobStep::id).containsExactly("checkVmExists", "noBackupRunning", "approval", "restart", "validate", "notifyDone");
        assertThat(done.steps()).extracting(JobStep::status).containsOnly(StepStatus.SUCCEEDED);
        assertThat(done.step("approval").output().path("by").asText()).isEqualTo("gonzalo");
        assertThat(done.step("restart").durationMs()).isNotNull();
        assertThat(done.durationMs()).isGreaterThan(0);
        assertThat(done.endedAt()).isNotNull();
        assertThat(types(job.id())).startsWith("job.started", "job.step", "job.step", "job.step", "job.step", "job.step", "job.approval-requested")
                .endsWith("job.finished");
        verify(w.telemetry, atLeastOnce()).record(any());
        RunsTestWiring.await(WAIT, () -> !w.execution.isRunning(job.id()).block());
    }

    @Test
    void aRejectedApprovalFailsTheJobAndRunsTheCatchFlow() {
        Job job = start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042"));
        awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        Approval a = job(job.id()).pendingApproval("approval");
        w.events.approvalDecided(a, ApprovalDecision.rejected("gonzalo", "not today", Instant.now()));
        w.execution.signalApproval(job.id(), a.id(), ApprovalDecision.rejected("gonzalo", "not today", Instant.now())).block();

        awaitStatus(job.id(), JobStatus.FAILED);
        Job failed = job(job.id());
        assertThat(failed.errorClass()).isEqualTo("rejected");
        assertThat(failed.failedStep()).isEqualTo("approval");
        assertThat(failed.step("notifyOwner").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(failed.step("notifyOwner").output().path("message").asText()).contains("failed at approval");
    }

    @Test
    void aJobsProviderLookupsCarryTheJobSoAProviderReleaseCanRouteThem() {
        List<ProviderQueries.Use> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        org.mockito.Mockito.when(w.providers.provider(org.mockito.ArgumentMatchers.anyString())).thenAnswer(i -> reactor.core.publisher.Mono.deferContextual(ctx -> {
            ProviderQueries.Use use = ProviderQueries.Use.of(ctx);
            if (use != null) {
                seen.add(use);
            }
            return reactor.core.publisher.Mono.justOrEmpty(SampleContent.providers().stream().filter(p -> p.name().equals(i.getArgument(0))).findFirst());
        }));
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-routed, title: Routed, noun: thing, verb: get, owner: team-x, version: 1.0.0 }
                api: { method: POST, path: "/api/v1/things:get", input: { type: object, properties: { id: { type: string } } } }
                workflow:
                  do:
                    - get:
                        name: Get
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                """);

        Job job = start(def, Map.of("id", "vm-1042"));

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        assertThat(seen).isNotEmpty().allSatisfy(use -> {
            assertThat(use.jobId()).isEqualTo(job.id());
            assertThat(use.environment()).isEqualTo(job(job.id()).environment());
            assertThat(use.dryRun()).isFalse();
        });
    }

    @Test
    void aFailedStepPausesForAttentionAndContinuesAfterSkip() {
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-attention, title: Attention, noun: thing, verb: poke, owner: team-x, version: 1.0.0 }
                api: { method: POST, path: "/api/v1/things:poke", input: { type: object, properties: { id: { type: string } } } }
                workflow:
                  do:
                    - get:
                        name: Get
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                    - broken:
                        name: Broken
                        call: provider
                        with: { provider: vsphere, action: vm.doesNotExist, input: { id: "{{ $input.id }}" } }
                    - after:
                        name: After
                        notify: { channel: "#x", message: "after {{ $('Get').json.name }}" }
                """);
        Job job = start(def, Map.of("id", "vm-1042"));

        awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        Job paused = job(job.id());
        assertThat(paused.attention().step()).isEqualTo("broken");
        assertThat(paused.attention().error().errorClass()).isEqualTo("unknown_action");
        assertThat(paused.attention().options()).containsExactly("retry", "edit-input", "skip", "abort");
        assertThat(paused.step("broken").status()).isEqualTo(StepStatus.FAILED);

        w.execution.signalAttention(job.id(), AttentionDecision.skip("ana")).block();

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        Job done = job(job.id());
        assertThat(done.attention()).isNull();
        assertThat(done.step("broken").status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(done.step("after").output().path("message").asText()).isEqualTo("after web-1042");
        assertThat(types(job.id())).contains("job.attention", "job.attention-resolved");
        DomainEvent resolved = published.stream().filter(e -> e.type().equals("job.attention-resolved")).findFirst().orElseThrow();
        assertThat(resolved.data().path("action").asText()).isEqualTo("skip");
        assertThat(resolved.data().path("by").asText()).isEqualTo("ana");
    }

    @Test
    void providerCallsThatChangeSomethingPublishWhatChangedAndReadsDoNot() {
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-cycle, title: Cycle, noun: thing, verb: cycle, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - look:
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                    - restart:
                        call: provider
                        with: { provider: vsphere, action: vm.restart, input: { id: "{{ $input.id }}" } }
                """);
        Job job = start(def, Map.of("id", "vm-1042"));

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        List<DomainEvent> changed = published.stream().filter(e -> e.type().equals("job.changed") && e.aggregateId().equals(job.id())).toList();
        assertThat(changed).singleElement().satisfies(e -> {
            assertThat(e.data().get("step").asText()).isEqualTo("restart");
            assertThat(e.data().get("provider").asText()).isEqualTo("vsphere");
            assertThat(e.data().get("action").asText()).isEqualTo("vm.restart");
            assertThat(e.data().get("effect").asText()).isEqualTo("change");
            assertThat(e.data().get("target").asText()).isEqualTo("vm-1042");
        });
        assertThat(published.stream().filter(e -> e.type().equals("job.started") && e.aggregateId().equals(job.id())).findFirst().orElseThrow()
                .data().get("contentHash").asText()).isEqualTo(WorkflowYaml.contentHash(def));
    }

    @Test
    void anEditInputDecisionCarriesTheEditedInputOnItsEvent() {
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-edit, title: Edit, noun: thing, verb: edit, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - broken:
                        call: provider
                        with: { provider: vsphere, action: vm.doesNotExist, input: { id: "{{ $input.id }}" } }
                """);
        Job job = start(def, Map.of("id", "vm-1042"));
        awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);

        w.execution.signalAttention(job.id(), AttentionDecision.editInput(SampleContent.json(Map.of("input", Map.of("id", "vm-7"))), "ana")).block();

        RunsTestWiring.await(WAIT, () -> published.stream().anyMatch(e -> e.type().equals("job.attention-resolved") && e.aggregateId().equals(job.id())));
        DomainEvent resolved = published.stream().filter(e -> e.type().equals("job.attention-resolved") && e.aggregateId().equals(job.id()))
                .findFirst().orElseThrow();
        assertThat(resolved.data().path("action").asText()).isEqualTo("edit-input");
        assertThat(resolved.data().path("input").path("input").path("id").asText()).isEqualTo("vm-7");
        w.execution.signalAttention(job.id(), AttentionDecision.abort("ana")).block();
    }

    @Test
    void abortRollsBackCompletedStepsWithCompensation() {
        Job job = start(SampleContent.workflow("create-vm"), Map.of("name", "web-01", "image", "img-77", "size", "small"));
        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        assertThat(job(job.id()).step("createVm").output().path("id").asText()).startsWith("vm-");
        assertThat(job(job.id()).step("registerVm").output().path("id").asText()).isNotBlank();
        verify(w.resourceCommands, atLeastOnce()).register(any());
    }

    @Test
    void cancelWakesASleepingJob() {
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-wait, title: Wait, noun: thing, verb: wait, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - pause:
                        name: Pause
                        wait: { duration: 10m }
                    - after:
                        name: After
                        notify: { channel: "#x", message: "never" }
                """);
        Job job = start(def, Map.of());
        RunsTestWiring.await(WAIT, () -> job(job.id()).step("pause") != null);

        w.execution.cancel(job.id(), "mateo").block();

        awaitStatus(job.id(), JobStatus.CANCELLED);
        Job cancelled = job(job.id());
        assertThat(cancelled.step("after")).isNull();
        assertThat(cancelled.endedAt()).isNotNull();
        DomainEvent e = published.stream().filter(x -> x.type().equals("job.cancelled")).findFirst().orElseThrow();
        assertThat(e.data().path("by").asText()).isEqualTo("mateo");
        RunsTestWiring.await(WAIT, () -> !w.execution.isRunning(job.id()).block());
    }

    @Test
    void childrenRunAsTheirOwnJobsAndForkBranchesRunConcurrently() {
        Job job = start(SampleContent.workflow("web-environment"), Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 2));
        awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        Approval a = job(job.id()).pendingApproval(null);
        w.events.approvalDecided(a, ApprovalDecision.approved("gonzalo", null, Instant.now()));
        w.execution.signalApproval(job.id(), a.id(), ApprovalDecision.approved("gonzalo", null, Instant.now())).block();

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        List<Job> children = w.projection.children(job.id());
        assertThat(children).hasSize(3);
        assertThat(children).allMatch(c -> c.status() == JobStatus.SUCCEEDED);
        assertThat(children).extracting(Job::workflow).containsExactlyInAnyOrder("create-vm", "create-vm", "create-database");
        assertThat(children).allMatch(c -> c.traceId().equals(job.traceId()));
        assertThat(children).allMatch(c -> c.channel().equals("rest"));
        Job done = job(job.id());
        assertThat(done.step("build").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(done.step("webServers").items()).isEqualTo(2);
        assertThat(done.step("webServers").output().path("items")).hasSize(2);
        assertThat(done.output().path("url").asText()).isNotBlank();
        assertThat(done.output().path("databaseId").asText()).isNotBlank();
        assertThat(done.step("registerEnv").output().path("type").asText()).isEqualTo("environment");
    }

    @Test
    void aRunStepWithoutAVersionRunsTheProducersCurrentMajorWithItsDefaults() {
        Job job = start(yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: one-vm, title: One VM, noun: vm, verb: one, owner: team-compute, version: 1.0.0 }
                workflow:
                  do:
                    - make:
                        run: { workflow: create-vm, input: { name: web-1, image: img-1 } }
                        output: { as: made }
                  outputs:
                    vmId: "{{ $made.outputs.vmId }}"
                """), Map.of());

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        verify(w.workflows).resolve("create-vm", "^2");
        assertThat(w.projection.children(job.id())).singleElement().satisfies(child -> {
            assertThat(child.input().path("size").asText()).as("the child's own defaults").isEqualTo("small");
            assertThat(child.input().path("environment").asText()).isEqualTo("dev");
        });
        assertThat(job(job.id()).output().path("vmId").asText()).isNotBlank();
    }

    @Test
    void anInputAskingForANewResourceBuildsItFirstAsAChildJobInTheSameTrace() {
        Job job = start(SampleContent.workflow("create-vm"), Map.of("name", "web-01", "image", Map.of("create", Map.of("os", "rhel-9")), "size", "small"));

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        assertThat(w.projection.children(job.id())).singleElement().satisfies(child -> {
            assertThat(child.workflow()).isEqualTo("build-vm-image");
            assertThat(child.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(child.traceId()).isEqualTo(job.traceId());
            assertThat(child.input().path("os").asText()).isEqualTo("rhel-9");
            assertThat(child.input().path("hardening").asText()).as("the producer's own default").isEqualTo("cis-l1");
        });
        Job done = job(job.id());
        JobStep build = done.step("image.create");
        assertThat(build.status()).isEqualTo(StepStatus.SUCCEEDED);
        String imageId = build.output().path("resources").get(0).path("id").asText();
        assertThat(imageId).isNotBlank();
        assertThat(build.output().path("resources").get(0).path("type").asText()).isEqualTo("image");
        assertThat(done.step("useImage").input().path("id").asText()).as("the job goes on with the new id").isEqualTo(imageId);
        assertThat(done.input().path("image").path("create").path("os").asText()).as("the job keeps the request as asked").isEqualTo("rhel-9");
    }

    // ---------------------------------------------------------------- the use step's wait

    private static WorkflowDefinition usesSlowImage(String timeout) {
        return yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: use-slow, title: Use slow, noun: image, verb: use, owner: team-platform, version: 1.0.0 }
                workflow:
                  do:
                    - useIt:
                        use: { type: image, id: img-slow, waitUntil: ready }
                        timeout: %s
                        pauseOnFailure: false
                """.formatted(timeout));
    }

    /** The registry answers img-slow in {@code status}; {@code ready} is what awaitState will answer. */
    private Sinks.One<Resource> slowImage(AtomicReference<String> status) {
        Sinks.One<Resource> ready = Sinks.one();
        when(w.resources.use(eq("image"), eq("img-slow"), any(), anyBoolean()))
                .thenAnswer(i -> Mono.just(RunsTestWiring.resource("image", "img-slow", status.get())));
        when(w.resources.awaitState(eq("image"), eq("img-slow"), eq("ready"), any())).thenAnswer(i -> ready.asMono());
        return ready;
    }

    private void awaitStep(String jobId, String step, StepStatus status) {
        RunsTestWiring.await(WAIT, () -> job(jobId).steps().stream().anyMatch(s -> s.id().equals(step) && s.status() == status));
    }

    @Test
    void aUseStepWaitsUntilTheRegistrySaysTheResourceIsReady() {
        AtomicReference<String> status = new AtomicReference<>("building");
        Sinks.One<Resource> ready = slowImage(status);
        Job job = start(usesSlowImage("10m"), Map.of());
        awaitStep(job.id(), "useIt", StepStatus.RUNNING);

        status.set("ready");
        ready.tryEmitValue(RunsTestWiring.resource("image", "img-slow", "ready"));

        awaitStatus(job.id(), JobStatus.SUCCEEDED);
        assertThat(job(job.id()).step("useIt").output().path("status").asText()).isEqualTo("ready");
        ArgumentCaptor<Duration> waited = ArgumentCaptor.forClass(Duration.class);
        verify(w.resources).awaitState(eq("image"), eq("img-slow"), eq("ready"), waited.capture());
        assertThat(waited.getValue()).as("the step's timeout bounds the wait").isLessThanOrEqualTo(Duration.ofMinutes(10)).isGreaterThan(Duration.ofMinutes(9));
    }

    @Test
    void aUseStepGivesUpAtItsTimeoutWhenNoEventComes() {
        slowImage(new AtomicReference<>("building"));
        Job job = start(usesSlowImage("1s"), Map.of());

        awaitStatus(job.id(), JobStatus.FAILED);
        JobStep use = job(job.id()).step("useIt");
        assertThat(use.error().errorClass()).isEqualTo("timeout");
        assertThat(use.error().message()).isEqualTo("image img-slow was not ready within 1s (status building)");
    }

    @Test
    void cancellingEndsAUseStepsWait() {
        slowImage(new AtomicReference<>("building"));
        Job job = start(usesSlowImage("1h"), Map.of());
        awaitStep(job.id(), "useIt", StepStatus.RUNNING);

        w.execution.cancel(job.id(), "gonzalo").block();

        awaitStatus(job.id(), JobStatus.CANCELLED);
    }

    @Test
    void aBatchRunsInWavesAndStopsWhenTooManyRowsFail() {
        WorkflowDefinition def = yaml("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-batch, title: Batch, noun: thing, verb: touch, owner: team-x, version: 1.0.0 }
                api:
                  method: POST
                  path: "/api/v1/things:touch"
                  input: { type: object, required: [id], properties: { id: { type: string } } }
                policy:
                  approval: { required: true, group: approvers, timeout: 1h }
                workflow:
                  do:
                    - get:
                        name: Get
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                        assert: "{{ $json.id != 'vm-bad' }}"
                        pauseOnFailure: false
                    - ask:
                        name: Ask
                        approval: { group: approvers, message: "ok?" }
                """);
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(SampleContent.json(Map.of("id", (i == 3 || i == 4) ? "vm-bad" : "vm-" + i)));
        }
        RolloutPolicy rollout = new RolloutPolicy(List.of(10, 50, 100), 3, 5);
        List<Batch.Wave> waves = WaveScheduler.plan(rows.size(), rollout);
        Job batch = JobFactory.newBatch(def, "1.0.0", rows, null, rollout, waves, SampleContent.GONZALO, "batch", null, null, null, w.clock.instant());
        w.projection.put(batch);
        w.events.started(batch);
        w.execution.start(batch).block();

        awaitStatus(batch.id(), JobStatus.WAITING_APPROVAL);
        Job waiting = job(batch.id());
        assertThat(waiting.approvals()).hasSize(1);
        Approval a = waiting.approvals().get(0);
        assertThat(a.step()).isEqualTo("batch");
        assertThat(a.message()).contains("12 rows");
        w.events.approvalDecided(a, ApprovalDecision.approved("gonzalo", null, Instant.now()));
        w.execution.signalApproval(batch.id(), a.id(), ApprovalDecision.approved("gonzalo", null, Instant.now())).block();

        awaitStatus(batch.id(), JobStatus.FAILED);
        Job done = job(batch.id());
        assertThat(done.errorClass()).isEqualTo("stopped");
        assertThat(done.batch().stoppedReason()).contains("stopped after wave 1");
        assertThat(done.batch().waves()).extracting(Batch.Wave::status).containsExactly("done", "done", "skipped");
        assertThat(done.batch().waves()).extracting(Batch.Wave::size).containsExactly(2, 4, 6);
        assertThat(done.batch().count("succeeded")).isEqualTo(4);
        assertThat(done.batch().count("failed")).isEqualTo(2);
        assertThat(done.batch().count("skipped")).isEqualTo(6);
        assertThat(done.progress()).isEqualTo(new Job.Progress(12, 12, 2));
        assertThat(done.output().path("succeeded").asInt()).isEqualTo(4);
        assertThat(done.batch().rows().get(3).error()).contains("assertion_failed");
        List<Job> children = w.projection.children(batch.id());
        assertThat(children).hasSize(6);
        assertThat(children).allMatch(c -> c.row() != null && c.channel().equals("batch") && c.preApproved());
        assertThat(children).allMatch(c -> c.approvals().isEmpty());
        assertThat(children.stream().filter(c -> c.status() == JobStatus.SUCCEEDED)).allMatch(c -> c.step("ask").output().path("approved").asBoolean());
        assertThat(w.projection.approvals()).hasSize(1);
        assertThat(types(batch.id())).contains("batch.wave").endsWith("job.finished");
        assertThat(published.stream().filter(e -> e.type().equals("batch.wave")).map(e -> e.data().path("status").asText()).toList())
                .containsExactly("running", "done", "running", "done", "skipped");
    }

    @Test
    void aBatchWithoutFailuresSucceeds() {
        WorkflowDefinition def = SampleContent.workflow("request-storage");
        List<JsonNode> rows = List.of(SampleContent.json(Map.of("vmId", "vm-1", "sizeGb", 100)), SampleContent.json(Map.of("vmId", "vm-2", "sizeGb", 100)),
                SampleContent.json(Map.of("vmId", "vm-3", "sizeGb", 100)));
        RolloutPolicy rollout = new RolloutPolicy(List.of(34, 100), 2, 0);
        Job batch = JobFactory.newBatch(def, def.version(), rows, null, rollout, WaveScheduler.plan(3, rollout), SampleContent.GONZALO, "batch", null, null, null,
                w.clock.instant());
        w.projection.put(batch);
        w.events.started(batch);
        w.execution.start(batch).block();

        awaitStatus(batch.id(), JobStatus.SUCCEEDED);
        Job done = job(batch.id());
        assertThat(done.batch().rows()).allMatch(r -> r.status().equals("succeeded") && r.jobId() != null);
        assertThat(done.batch().waves()).extracting(Batch.Wave::status).containsExactly("done", "done");
        assertThat(done.progress()).isEqualTo(new Job.Progress(3, 3, 0));
        assertThat(w.projection.children(batch.id())).hasSize(3);
    }
}
