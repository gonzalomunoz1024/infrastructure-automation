package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries.Lineage;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.domain.InvalidInputException;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.Preview.AffectedResource;
import com.iaf.platform.runs.domain.Preview.PreviewStep;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerResult;
import com.iaf.platform.runs.ports.outbound.WorkflowExecutionPort;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The write side over a mocked engine: what a start validates, stores and publishes, and how the steering commands signal. */
class RunsCommandHandlerTest {

    private static final Identity NOBODY = new Identity("nobody", "Nobody", null, List.of(), List.of(), false, Identity.Kind.USER);
    /** Neither in team-compute nor an ops, sre or compute-approver: may steer only the jobs they started. */
    private static final Identity OUTSIDER = new Identity("outsider", "Outsider", null, List.of("team-web"), List.of("developer"), false, Identity.Kind.USER);
    /** In the owner team of vm-restart without any of its rbac roles. */
    private static final Identity COMPUTE_DEV = new Identity("dev", "Dev", null, List.of("team-compute"), List.of("developer"), false, Identity.Kind.USER);

    private RunsTestWiring w;
    private WorkflowExecutionPort engine;
    private RunsCommandHandler handler;
    private final List<DomainEvent> published = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        w = new RunsTestWiring();
        engine = RunsTestWiring.idleEngine();
        handler = w.commands(engine);
        w.bus.register(published::add);
    }

    private List<String> eventTypes() {
        return published.stream().map(DomainEvent::type).toList();
    }

    // ---------------------------------------------------------------- startJob

    @Test
    void startJobRejectsAResourceTheRegistryDoesNotKnow() {
        StartJob unknown = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "missing-vm-9")), SampleContent.GONZALO,
                "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(unknown))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InvalidInputException.class).hasMessageContaining("missing-vm-9");
                    assertThat(((InvalidInputException) e).problems()).extracting(p -> p.code()).containsExactly("input.unknownResource");
                })
                .verify();
        StartJob wrongType = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "db-9")), SampleContent.GONZALO,
                "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(wrongType))
                .expectErrorSatisfies(e -> assertThat(((InvalidInputException) e).problems()).extracting(p -> p.code()).containsExactly("input.wrongResourceType"))
                .verify();
        assertThat(eventTypes()).doesNotContain("job.started");
    }

    @Test
    void aBatchNamesEveryRowWhoseResourceIsUnknownOrRetired() {
        StartBatch batch = new StartBatch("vm-restart", null,
                List.of(SampleContent.json(Map.of("vmId", "vm-1")), SampleContent.json(Map.of("vmId", "missing-vm-2")), SampleContent.json(Map.of("vmId", "retired-vm-3"))),
                null, null, SampleContent.GONZALO, "batch", null);
        StepVerifier.create(handler.startBatch(batch))
                .expectErrorSatisfies(e -> {
                    InvalidInputException ii = (InvalidInputException) e;
                    assertThat(ii.rows()).extracting(r -> r.index()).containsExactly(1, 2);
                    assertThat(ii.rows().get(1).problems().get(0).code()).isEqualTo("input.retiredResource");
                })
                .verify();
        assertThat(eventTypes()).doesNotContain("job.started");
    }

    @Test
    void startJobValidatesStoresSnapshotsOpensChangeAndStartsTheEngine() {
        StartJob cmd = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "vm-1042", "reason", "stuck")), SampleContent.GONZALO,
                "catalog", null, "trace-1", null, null);

        CommandAccepted acc = handler.startJob(cmd).block();

        assertThat(acc.status()).isEqualTo("queued");
        assertThat(acc.links()).containsEntry("job", "/api/v1/jobs/" + acc.id());
        Job job = w.projection.get(acc.id()).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.definition()).isNotNull();
        assertThat(job.definition().name()).isEqualTo("vm-restart");
        assertThat(job.version()).isEqualTo(job.definition().version());
        assertThat(job.input().path("environment").asText()).isEqualTo("dev");
        assertThat(job.environment()).isEqualTo("dev");
        assertThat(job.traceId()).isEqualTo("trace-1");
        assertThat(job.channel()).isEqualTo("catalog");
        assertThat(job.preview()).isNotNull();
        assertThat(job.preview().steps()).extracting(Preview.PreviewStep::id).contains("checkVmExists", "restart", "validate");
        assertThat(job.change()).isNotNull();
        assertThat(job.change().id()).startsWith("CHG00");
        assertThat(job.change().url()).contains(job.change().id());
        assertThat(job.change().evidenceUrl()).isEqualTo("http://localhost:3000/#/audit/" + acc.id());
        assertThat(job.policy().decision()).isEqualTo("allow");
        assertThat(eventTypes()).containsExactly("job.started", "job.change-opened");
        assertThat(published.get(0).data().path("workflow").asText()).isEqualTo("vm-restart");
        assertThat(published.get(0).data().path("requester").asText()).isEqualTo("gonzalo");
        assertThat(published.get(0).data().path("kind").asText()).isEqualTo("single");
        assertThat(published.get(0).data().has("preview")).isTrue();
        assertThat(published.get(0).data().path("change").path("id").asText()).isEqualTo(job.change().id());
        assertThat(published.get(1).data().path("change").path("evidenceUrl").asText()).isEqualTo(job.change().evidenceUrl());
        ArgumentCaptor<Job> started = ArgumentCaptor.forClass(Job.class);
        verify(engine).start(started.capture());
        assertThat(started.getValue().id()).isEqualTo(acc.id());
    }

    @Test
    void startJobRejectsInvalidInputWithEveryProblemAndStartsNothing() {
        StartJob cmd = new StartJob("vm-restart", null, SampleContent.json(Map.of("reason", "x", "environment", "staging")), SampleContent.GONZALO,
                "rest", null, null, null, null);

        StepVerifier.create(handler.startJob(cmd))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IllegalArgumentException.class);
                    assertThat(e.getMessage()).contains("problems").contains("vmId").contains("environment");
                })
                .verify();
        verify(engine, never()).start(any());
        assertThat(published).isEmpty();
    }

    @Test
    void startJobEnforcesRbacAndPolicy() {
        StartJob cmd = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "vm-1042")), NOBODY, "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(cmd)).expectError(ForbiddenException.class).verify();

        when(w.policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("prod-freeze", "deny", "Change freeze until Monday")), "mock")));
        StartJob ok = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "vm-1042")), SampleContent.GONZALO, "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(ok))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining("Change freeze"))
                .verify();
        verify(engine, never()).start(any());
    }

    @Test
    void startJobRoutesTheVersionThroughReleasesAndFailsOnUnknownWorkflow() {
        WorkflowDefinition old = SampleContent.workflow("vm-restart").withVersion("1.1.0");
        when(w.workflows.version("vm-restart", "1.1.0")).thenReturn(Mono.just(RunsTestWiring.record(old)));
        when(w.releases.route(eq("vm-restart"), anyString())).thenReturn(Mono.just("1.1.0"));

        CommandAccepted acc = handler.startJob(new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", "vm-1042")),
                SampleContent.GONZALO, "rest", null, null, null, null)).block();

        assertThat(w.projection.get(acc.id()).orElseThrow().version()).isEqualTo("1.1.0");
        verify(w.workflows).version("vm-restart", "1.1.0");

        StepVerifier.create(handler.startJob(new StartJob("no-such-workflow", null, null, SampleContent.GONZALO, "rest", null, null, null, null)))
                .expectError(NotFoundException.class)
                .verify();
    }

    @Test
    void startJobWithAnExplicitVersionAndChangeIdSkipsRoutingAndTheChangeSystem() {
        String version = SampleContent.workflow("delete-vm").version();
        CommandAccepted acc = handler.startJob(new StartJob("delete-vm", version, SampleContent.json(Map.of("vmId", "vm-7", "reason", "decommissioned for good")),
                SampleContent.GONZALO, "mcp", null, null, null, "CHG0099999")).block();

        Job job = w.projection.get(acc.id()).orElseThrow();
        assertThat(job.change().id()).isEqualTo("CHG0099999");
        assertThat(job.change().state()).isEqualTo("linked");
        assertThat(w.changes.tickets()).isEmpty();
        verify(w.releases, never()).route(anyString(), anyString());
    }

    @Test
    void startJobTakesARequestToBuildTheResourceFirstAndJudgesItAgainstTheProducer() {
        StartJob build = new StartJob("create-vm", null, SampleContent.json(Map.of("name", "web-01", "image", Map.of("create", Map.of("os", "rhel-9")))),
                SampleContent.GONZALO, "rest", null, null, null, null);
        CommandAccepted acc = handler.startJob(build).block();
        Job job = w.projection.get(acc.id()).orElseThrow();
        assertThat(job.input().path("image").path("create").path("os").asText()).as("the job keeps the request as asked").isEqualTo("rhel-9");
        verify(engine).start(any());

        StartJob wrong = new StartJob("create-vm", null, SampleContent.json(Map.of("name", "web-02", "image", Map.of("create", Map.of("os", "beos")))),
                SampleContent.GONZALO, "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(wrong))
                .expectErrorSatisfies(e -> assertThat(((InvalidInputException) e).problems())
                        .extracting(p -> p.code(), p -> p.field()).containsExactly(org.assertj.core.groups.Tuple.tuple("schema", "image.create.os")))
                .verify();
        StartJob notAnId = new StartJob("vm-restart", null, SampleContent.json(Map.of("vmId", Map.of("create", Map.of()))),
                SampleContent.GONZALO, "rest", null, null, null, null);
        StepVerifier.create(handler.startJob(notAnId))
                .expectErrorSatisfies(e -> assertThat(((InvalidInputException) e).problems()).extracting(p -> p.field()).contains("vmId"))
                .verify();
    }

    @Test
    void aBatchNamesEveryRowWhoseRequestToBuildTheProducerWouldRefuse() {
        List<JsonNode> items = List.of(
                SampleContent.json(Map.of("name", "web-01", "image", "img-1")),
                SampleContent.json(Map.of("name", "web-02", "image", Map.of("create", Map.of("hardening", "cis-l9")))),
                SampleContent.json(Map.of("name", "web-03", "image", Map.of("create", Map.of()))));
        StepVerifier.create(handler.startBatch(new StartBatch("create-vm", null, items, null, null, SampleContent.GONZALO, "batch", null)))
                .expectErrorSatisfies(e -> {
                    InvalidInputException ii = (InvalidInputException) e;
                    assertThat(ii.rows()).extracting(r -> r.index()).containsExactly(1);
                    assertThat(ii.rows().get(0).problems()).extracting(p -> p.field()).containsExactly("image.create.hardening");
                })
                .verify();
        verify(engine, never()).start(any());
    }

    // ---------------------------------------------------------------- startBatch

    @Test
    void startBatchRejectsEveryInvalidRowBeforeAnythingStarts() {
        List<JsonNode> items = List.of(
                SampleContent.json(Map.of("vmId", "vm-1")),
                SampleContent.json(Map.of("reason", "no id")),
                SampleContent.json(Map.of("vmId", "vm-3", "environment", "moon")));
        StepVerifier.create(handler.startBatch(new StartBatch("vm-restart", null, items, null, null, SampleContent.GONZALO, "batch", null)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(IllegalArgumentException.class);
                    assertThat(e.getMessage()).contains("rows").contains("\"index\":1").contains("\"index\":2").doesNotContain("\"index\":0");
                })
                .verify();
        verify(engine, never()).start(any());
        assertThat(w.projection.all()).isEmpty();
    }

    @Test
    void startBatchBuildsOneParentWithRowsInWavesAndDefaultsMerged() {
        List<JsonNode> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(SampleContent.json(Map.of("vmId", "vm-" + i)));
        }
        JsonNode defaults = SampleContent.json(Map.of("cve", "CVE-2026-1234", "severity", "critical"));

        CommandAccepted acc = handler.startBatch(new StartBatch("patch-cve", null, items, defaults, null, SampleContent.GONZALO, "batch", null)).block();

        assertThat(acc.links()).containsEntry("rows", "12");
        Job job = w.projection.get(acc.id()).orElseThrow();
        assertThat(job.kind()).isEqualTo(Job.Kind.BATCH);
        assertThat(job.progress()).isEqualTo(new Job.Progress(0, 12, 0));
        Batch b = job.batch();
        assertThat(b.rows()).hasSize(12);
        assertThat(b.rows().get(3).input().path("cve").asText()).isEqualTo("CVE-2026-1234");
        assertThat(b.rows().get(3).input().path("vmId").asText()).isEqualTo("vm-3");
        assertThat(b.waves()).extracting(Batch.Wave::size).containsExactly(1, 2, 9);
        assertThat(b.rows().stream().filter(r -> r.wave() == 0).count()).isEqualTo(1);
        assertThat(b.rows().stream().filter(r -> r.wave() == 2).count()).isEqualTo(9);
        assertThat(b.rollout().maxParallel()).isEqualTo(20);
        assertThat(job.preview()).isNotNull();
        assertThat(job.preview().affectedResources()).hasSize(12);
        assertThat(eventTypes()).containsExactly("job.started");
        assertThat(published.get(0).data().path("kind").asText()).isEqualTo("batch");
        assertThat(published.get(0).data().path("rows").asInt()).isEqualTo(12);
        verify(engine).start(any());
    }

    @Test
    void startBatchHonoursTheRolloutOverrideAndTheMaximum() {
        List<JsonNode> items = List.of(SampleContent.json(Map.of("vmId", "a")), SampleContent.json(Map.of("vmId", "b")), SampleContent.json(Map.of("vmId", "c")));
        CommandAccepted acc = handler.startBatch(new StartBatch("vm-restart", null, items, null, new RolloutPolicy(List.of(50, 100), 1, 0),
                SampleContent.GONZALO, "batch", null)).block();
        Batch b = w.projection.get(acc.id()).orElseThrow().batch();
        assertThat(b.waves()).extracting(Batch.Wave::size).containsExactly(2, 1);
        assertThat(b.rollout().maxParallel()).isEqualTo(1);

        List<JsonNode> tooMany = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add(SampleContent.json(Map.of("clusterId", "db-" + i, "targetVersion", "8.0")));
        }
        StepVerifier.create(handler.startBatch(new StartBatch("mongodb-upgrade", null, tooMany, null, null, SampleContent.GONZALO, "batch", null)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 50"))
                .verify();
    }

    // ---------------------------------------------------------------- approvals

    private Approval pendingApproval(String jobId) {
        Approval a = Approval.builder().id("apr_1").jobId(jobId).workflow("vm-restart").step("approval").group("compute-approvers")
                .message("Restart?").requestedAt(Instant.now()).timeout("4h").status(Approval.Status.PENDING).build();
        w.events.approvalRequested(a);
        return a;
    }

    private String startedJob(String workflow, Map<String, Object> input) {
        return handler.startJob(new StartJob(workflow, null, SampleContent.json(input), SampleContent.GONZALO, "rest", null, null, null, null)).block().id();
    }

    @Test
    void approveChecksTheGroupPublishesTheDecisionAndSignalsTheEngine() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        Approval a = pendingApproval(jobId);
        assertThat(w.projection.get(jobId).orElseThrow().status()).isEqualTo(JobStatus.WAITING_APPROVAL);
        when(engine.isRunning(jobId)).thenReturn(Mono.just(true));

        StepVerifier.create(handler.approve(a.id(), "fine").contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)))
                .expectError(ForbiddenException.class)
                .verify();

        CommandAccepted acc = handler.approve(a.id(), "fine").contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();

        assertThat(acc.id()).isEqualTo("apr_1");
        assertThat(acc.status()).isEqualTo("approved");
        Approval decided = w.projection.approval("apr_1").orElseThrow();
        assertThat(decided.status()).isEqualTo(Approval.Status.APPROVED);
        assertThat(decided.decidedBy()).isEqualTo("gonzalo");
        assertThat(decided.comment()).isEqualTo("fine");
        ArgumentCaptor<ApprovalDecision> decision = ArgumentCaptor.forClass(ApprovalDecision.class);
        verify(engine).signalApproval(eq(jobId), eq("apr_1"), decision.capture());
        assertThat(decision.getValue().approved()).isTrue();
        assertThat(eventTypes()).contains("job.approval-decided");

        StepVerifier.create(handler.reject(a.id(), "again").contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)))
                .expectError(ConflictException.class)
                .verify();
        StepVerifier.create(handler.approve("apr_missing", null)).expectError(NotFoundException.class).verify();
    }

    @Test
    void anEscalatedApprovalMayBeDecidedByTheEscalationGroupToo() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        Approval a = pendingApproval(jobId);
        StepVerifier.create(handler.approve(a.id(), "mine").contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)))
                .expectError(ForbiddenException.class)
                .verify();

        w.events.approvalEscalated(jobId, a.id(), a.step(), "data-approvers");

        Approval escalated = w.projection.approval(a.id()).orElseThrow();
        assertThat(escalated.escalatedTo()).isEqualTo("data-approvers");
        assertThat(escalated.approverGroups()).containsExactly("compute-approvers", "data-approvers");
        assertThat(w.queries.approval(a.id()).block().get("escalatedTo").asText()).isEqualTo("data-approvers");
        CommandAccepted acc = handler.approve(a.id(), "covering").contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)).block();
        assertThat(acc.status()).isEqualTo("approved");
        assertThat(w.projection.approval(a.id()).orElseThrow().decidedBy()).isEqualTo("ana");
    }

    @Test
    void rejectRecordsTheDecisionAsRejected() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        Approval a = pendingApproval(jobId);
        CommandAccepted acc = handler.reject(a.id(), "not now").contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();
        assertThat(acc.status()).isEqualTo("rejected");
        assertThat(w.projection.approval("apr_1").orElseThrow().status()).isEqualTo(Approval.Status.REJECTED);
    }

    // ---------------------------------------------------------------- cancel, retry, skip, abort

    @Test
    void cancelOfAJobTheEngineDoesNotHoldPublishesCancelledItself() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));

        CommandAccepted acc = handler.cancel(jobId).contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)).block();

        assertThat(acc.status()).isEqualTo("cancelled");
        Job job = w.projection.get(jobId).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(job.endedAt()).isNotNull();
        assertThat(published.stream().filter(e -> e.type().equals("job.cancelled")).findFirst().orElseThrow().data().path("by").asText()).isEqualTo("ana");
        verify(engine, never()).cancel(anyString(), anyString());

        StepVerifier.create(handler.cancel(jobId)).expectError(ConflictException.class).verify();
        StepVerifier.create(handler.cancel("job_nope")).expectError(NotFoundException.class).verify();
    }

    @Test
    void cancelOfARunningJobGoesThroughTheEngine() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        when(engine.isRunning(jobId)).thenReturn(Mono.just(true));
        handler.cancel(jobId).contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();
        verify(engine).cancel(jobId, "gonzalo");
        assertThat(eventTypes()).doesNotContain("job.cancelled");
    }

    @Test
    void aStrangerMayNotSteerAJobTheRequesterAndTheOwnerTeamMay() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        Job job = w.projection.update(jobId, j -> j.toBuilder().requester(OUTSIDER).build()).orElseThrow();
        when(engine.isRunning(jobId)).thenReturn(Mono.just(true));

        StepVerifier.create(handler.cancel(jobId).contextWrite(c -> CurrentIdentity.with(c, NOBODY)))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ForbiddenException.class).hasMessageContaining(jobId).hasMessageContaining("Nobody"))
                .verify();
        w.events.attention(jobId, new Attention("validate", new StepError("timeout", "not powered on", true), Attention.OPTIONS, Instant.now(), "P7D"));
        StepVerifier.create(handler.retry(jobId, "validate", null).contextWrite(c -> CurrentIdentity.with(c, NOBODY))).expectError(ForbiddenException.class).verify();
        StepVerifier.create(handler.skip(jobId, "validate").contextWrite(c -> CurrentIdentity.with(c, NOBODY))).expectError(ForbiddenException.class).verify();
        StepVerifier.create(handler.abort(jobId).contextWrite(c -> CurrentIdentity.with(c, NOBODY))).expectError(ForbiddenException.class).verify();
        verify(engine, never()).signalAttention(anyString(), any());
        verify(engine, never()).cancel(anyString(), anyString());

        assertThat(RunsCommandHandler.canControl(OUTSIDER, job)).as("the requester").isTrue();
        assertThat(RunsCommandHandler.canControl(COMPUTE_DEV, job)).as("the owner team").isTrue();
        assertThat(RunsCommandHandler.canControl(SampleContent.ANA, job)).as("an ops, named by policy.rbac").isTrue();
        assertThat(RunsCommandHandler.canControl(new Identity("ca", "Approver", null, List.of("compute-approvers"), List.of(), false, Identity.Kind.USER), job))
                .as("an approver of the workflow").isTrue();
        assertThat(RunsCommandHandler.canControl(NOBODY, job)).isFalse();
        assertThat(RunsCommandHandler.canControl(new Identity("web", "Web", null, List.of("team-web"), List.of("developer"), false, Identity.Kind.USER), job))
                .as("another team's developer").isFalse();

        handler.skip(jobId, "validate").contextWrite(c -> CurrentIdentity.with(c, OUTSIDER)).block();
        verify(engine).signalAttention(eq(jobId), argThat(d -> d.action() == AttentionDecision.Action.SKIP && d.by().equals("outsider")));
        handler.cancel(jobId).contextWrite(c -> CurrentIdentity.with(c, COMPUTE_DEV)).block();
        verify(engine).cancel(jobId, "dev");
    }

    @Test
    void retrySkipAndAbortNeedAJobWaitingForAttention() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042"));
        StepVerifier.create(handler.retry(jobId, null, null)).expectError(ConflictException.class).verify();

        w.events.attention(jobId, new Attention("validate", new StepError("timeout", "not powered on", true), Attention.OPTIONS, Instant.now(), "P7D"));
        assertThat(w.projection.get(jobId).orElseThrow().status()).isEqualTo(JobStatus.NEEDS_ATTENTION);
        when(engine.isRunning(jobId)).thenReturn(Mono.just(true));

        StepVerifier.create(handler.retry(jobId, "restart", null)).expectError(IllegalArgumentException.class).verify();

        handler.retry(jobId, "validate", null).contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();
        handler.retry(jobId, null, SampleContent.json(Map.of("input", Map.of("timeout", 1200)))).contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();
        handler.skip(jobId, "validate").contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)).block();
        CommandAccepted abort = handler.abort(jobId).contextWrite(c -> CurrentIdentity.with(c, SampleContent.ANA)).block();
        assertThat(abort.status()).isEqualTo("aborting");

        ArgumentCaptor<AttentionDecision> decisions = ArgumentCaptor.forClass(AttentionDecision.class);
        verify(engine, org.mockito.Mockito.times(4)).signalAttention(eq(jobId), decisions.capture());
        assertThat(decisions.getAllValues()).extracting(AttentionDecision::action).containsExactly(
                AttentionDecision.Action.RETRY, AttentionDecision.Action.EDIT_INPUT, AttentionDecision.Action.SKIP, AttentionDecision.Action.ABORT);
        assertThat(decisions.getAllValues().get(1).input().path("input").path("timeout").asInt()).isEqualTo(1200);
        assertThat(decisions.getAllValues().get(2).by()).isEqualTo("ana");

        when(engine.isRunning(jobId)).thenReturn(Mono.just(false));
        StepVerifier.create(handler.retry(jobId, null, null)).expectError(ConflictException.class).verify();
    }

    // ---------------------------------------------------------------- rerun and retryFailedRows

    @Test
    void rerunCopiesTheOutputsBeforeTheStepIntoANewJob() {
        String jobId = startedJob("vm-restart", Map.of("vmId", "vm-1042", "reason", "stuck"));
        Job source = w.projection.get(jobId).orElseThrow();
        List<JobStep> steps = List.of(
                JobStep.builder().id("checkVmExists").name("Check VM exists").type(StepType.PROVIDER).status(StepStatus.SUCCEEDED).attempt(1)
                        .output(SampleContent.json(Map.of("id", "vm-1042", "name", "web-1042"))).build(),
                JobStep.builder().id("noBackupRunning").type(StepType.PROVIDER).status(StepStatus.FAILED).attempt(1)
                        .error(new StepError("rate_limited", "429", true)).build());
        w.projection.put(source.toBuilder().status(JobStatus.FAILED).steps(steps).endedAt(Instant.now()).build());
        published.clear();

        StepVerifier.create(handler.rerun(jobId, "restart", null)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(handler.rerun(jobId, "largeSpec", null)).expectError(IllegalArgumentException.class).verify();

        CommandAccepted acc = handler.rerun(jobId, "noBackupRunning", SampleContent.json(Map.of("reason", "second try")))
                .contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();

        assertThat(acc.id()).isNotEqualTo(jobId);
        Job rerun = w.projection.get(acc.id()).orElseThrow();
        assertThat(rerun.resume().fromStep()).isEqualTo("noBackupRunning");
        assertThat(rerun.resume().seed()).hasSize(2);
        assertThat(rerun.input().path("reason").asText()).isEqualTo("second try");
        assertThat(rerun.input().path("vmId").asText()).isEqualTo("vm-1042");
        assertThat(rerun.steps()).isEmpty();
        assertThat(rerun.definition()).isSameAs(source.definition());
        assertThat(published.get(0).type()).isEqualTo("job.started");
        assertThat(published.get(0).data().path("rerunOf").asText()).isEqualTo(jobId);
        assertThat(published.get(0).data().path("resumedFrom").asText()).isEqualTo("noBackupRunning");

        String running = startedJob("vm-restart", Map.of("vmId", "vm-1"));
        StepVerifier.create(handler.rerun(running, "checkVmExists", null)).expectError(ConflictException.class).verify();
    }

    @Test
    void retryFailedRowsAddsAWaveWithTheFailedRowsAndRestartsTheBatch() {
        List<JsonNode> items = List.of(SampleContent.json(Map.of("vmId", "a")), SampleContent.json(Map.of("vmId", "b")),
                SampleContent.json(Map.of("vmId", "c")), SampleContent.json(Map.of("vmId", "d")));
        String batchId = handler.startBatch(new StartBatch("vm-restart", null, items, null, new RolloutPolicy(List.of(50, 100), 2, 50),
                SampleContent.GONZALO, "batch", null)).block().id();
        StepVerifier.create(handler.retryFailedRows(batchId)).expectError(ConflictException.class).verify();

        Job batch = w.projection.get(batchId).orElseThrow();
        List<Batch.BatchRow> rows = List.of(
                batch.batch().rows().get(0).withStatus("succeeded").withJobId("job_r0"),
                batch.batch().rows().get(1).withStatus("failed").withJobId("job_r1").withError("timeout at validate"),
                batch.batch().rows().get(2).withStatus("skipped"),
                batch.batch().rows().get(3).withStatus("skipped"));
        w.projection.put(batch.toBuilder().batch(batch.batch().withRows(rows).withStoppedReason("stopped")).status(JobStatus.FAILED).endedAt(Instant.now()).build());
        published.clear();

        CommandAccepted acc = handler.retryFailedRows(batchId).contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();

        assertThat(acc.id()).isEqualTo(batchId);
        assertThat(acc.status()).isEqualTo("queued");
        Job again = w.projection.get(batchId).orElseThrow();
        assertThat(again.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(again.batch().waves()).hasSize(3);
        Batch.Wave added = again.batch().waves().get(2);
        assertThat(added.index()).isEqualTo(2);
        assertThat(added.size()).isEqualTo(3);
        assertThat(added.status()).isEqualTo("pending");
        assertThat(again.batch().rowsOf(2)).extracting(Batch.BatchRow::index).containsExactly(1, 2, 3);
        assertThat(again.batch().rows().get(1).status()).isEqualTo("pending");
        assertThat(again.batch().rows().get(1).jobId()).isNull();
        assertThat(again.batch().rows().get(0).status()).isEqualTo("succeeded");
        assertThat(again.batch().stoppedReason()).isNull();
        assertThat(again.progress()).isEqualTo(new Job.Progress(1, 4, 0));
        assertThat(eventTypes()).containsExactly("batch.wave", "job.log");
        ArgumentCaptor<Job> restarted = ArgumentCaptor.forClass(Job.class);
        verify(engine, org.mockito.Mockito.times(2)).start(restarted.capture());
        assertThat(restarted.getAllValues().get(1).batch().waves()).hasSize(3);
    }

    // ---------------------------------------------------------------- dry run and preview

    @Test
    void dryRunWalksTheSamplesWithoutStartingAJobOrPublishing() {
        DryRunResult r = handler.dryRun(SampleContent.workflow("vm-restart"), SampleContent.json(Map.of("vmId", "vm-1042", "reason", "x")), SampleContent.GONZALO)
                .block();

        assertThat(r.passed()).isTrue();
        assertThat(r.error()).isNull();
        assertThat(r.path()).containsExactly("checkVmExists", "noBackupRunning", "approval", "restart", "validate", "notifyDone");
        assertThat(r.steps()).extracting(DryRunResult.DryRunStep::status).containsOnly("succeeded");
        assertThat(r.steps().get(2).output().path("by").asText()).isEqualTo("dry-run");
        assertThat(r.steps().get(0).output().path("name").asText()).isEqualTo("web-1042");
        assertThat(published).isEmpty();
        assertThat(w.projection.all()).isEmpty();
        verify(w.telemetry, never()).record(any());
        verify(w.resourceCommands, never()).register(any());
    }

    @Test
    void dryRunOfAChildBearingWorkflowPrefixesTheChildSteps() {
        DryRunResult r = handler.dryRun(SampleContent.workflow("web-environment"),
                SampleContent.json(Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 1)), SampleContent.GONZALO).block();
        assertThat(r.passed()).isTrue();
        assertThat(r.path()).anyMatch(p -> p.contains("/"));
    }

    @Test
    void dryRunReportsTheFailingStep() {
        // a verify step whose assertion the samples cannot satisfy
        WorkflowDefinition def = WorkflowYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-verify, title: Verify, noun: thing, verb: check, owner: team-x, version: 1.0.0 }
                api: { method: POST, path: "/api/v1/things:check", input: { type: object, properties: { id: { type: string } } } }
                workflow:
                  do:
                    - get:
                        name: Get
                        call: provider
                        with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                    - verify:
                        name: Verify
                        script:
                          language: javascript
                          code: "return { fixed: false };"
                        assert: "{{ $json.fixed == true }}"
                """);
        DryRunResult r = handler.dryRun(def, SampleContent.json(Map.of("id", "vm-1042")), SampleContent.GONZALO).block();
        assertThat(r.passed()).isFalse();
        assertThat(r.error()).contains("verify").contains("Assertion failed");
        DryRunResult.DryRunStep verify = r.steps().stream().filter(s -> s.id().equals("verify")).findFirst().orElseThrow();
        assertThat(verify.status()).isEqualTo("failed");
        assertThat(verify.error()).contains("Assertion failed");
        assertThat(r.steps().stream().filter(s -> s.id().equals("get")).findFirst().orElseThrow().status()).isEqualTo("succeeded");
    }

    @Test
    void previewRefusesAnIdTheRegistryDoesNotKnowLikeARun() {
        StepVerifier.create(handler.preview(SampleContent.workflow("vm-restart"), SampleContent.json(Map.of("vmId", "missing-vm-9")), SampleContent.GONZALO))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InvalidInputException.class).hasMessageContaining("missing-vm-9");
                    assertThat(((InvalidInputException) e).problems()).extracting(p -> p.code()).containsExactly("input.unknownResource");
                })
                .verify();
    }

    @Test
    void previewListsEffectsResourcesCostAndPolicy() {
        Preview p = handler.preview(SampleContent.workflow("vm-restart"), SampleContent.json(Map.of("vmId", "vm-1042")), SampleContent.GONZALO).block();

        assertThat(p.workflow()).isEqualTo("vm-restart");
        assertThat(p.policy().decision()).isEqualTo("allow");
        Map<String, String> effects = new java.util.HashMap<>();
        p.steps().forEach(s -> effects.put(s.id(), s.effect().json()));
        assertThat(effects).containsEntry("checkVmExists", "read").containsEntry("restart", "change").containsEntry("notifyDone", "none");
        assertThat(p.affectedResources()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("vm-1042");
            assertThat(r.type()).isEqualTo("vm");
            assertThat(r.name()).isEqualTo("name-of-vm-1042");
            assertThat(r.via()).isEqualTo("input.vmId");
        });
        assertThat(p.cost()).isNull();

        Preview create = handler.preview(SampleContent.workflow("create-vm"), SampleContent.json(Map.of("name", "web-01", "image", "img-77")), SampleContent.GONZALO).block();
        assertThat(create.cost()).isNotNull();
        assertThat(create.cost().monthly()).isEqualTo(38.5);
        assertThat(create.cost().currency()).isEqualTo("USD");
        assertThat(create.steps()).anyMatch(s -> s.id().equals("createVm") && s.effect().json().equals("create"));
    }

    private static PreviewStep step(Preview p, String id) {
        return p.steps().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void previewSkipsWhatTheDryRunDoesNotTakeAndPricesWithThePriceLists() {
        Preview p = handler.preview(SampleContent.workflow("create-vm"), SampleContent.json(Map.of("name", "web-01", "image", "img-77", "size", "medium")),
                SampleContent.GONZALO).block();

        assertThat(p.steps()).filteredOn(PreviewStep::skipped).extracting(PreviewStep::id).containsExactly("largeSpec", "smallSpec");
        assertThat(step(p, "largeSpec").effect().json()).isEqualTo("none");
        assertThat(p.cost().monthly()).as("a medium VM has 4 cpus").isEqualTo(77.0);
        assertThat(p.cost().detail()).isEqualTo("vsphere vm.create 77.00 (price list list-prices)");
    }

    @Test
    void previewAsksADeletesPlanWhatItWouldDelete() {
        JsonNode input = SampleContent.json(Map.of("vmId", "vm-1042", "reason", "retired"));

        Preview p = handler.preview(SampleContent.workflow("delete-vm"), input, SampleContent.GONZALO).block();

        assertThat(step(p, "delete").effect().json()).isEqualTo("delete");
        assertThat(step(p, "delete").detail()).isEqualTo("vsphere vm.delete (destructive) · plan vm.get: web-1042 (vm-1042)");
        assertThat(p.destructive()).isTrue();
        // the plan only reads, so it was a real call (dry runs leave no call sample); the dry run's own calls were not
        verify(w.telemetry).record(argThat(s -> s.action().equals("vm.get") && s.jobId() == null && s.ok()));
        verify(w.telemetry, never()).record(argThat(s -> s.action().equals("vm.delete")));

        // the VM is gone already: the plan finds nothing to delete
        ProviderDefinition vsphere = SampleContent.provider("vsphere");
        w.calls.world().call(vsphere, vsphere.action("vm.delete").orElseThrow(), SampleContent.json(Map.of("id", "vm-1042")), "job_x", 1, true);
        Preview gone = handler.preview(SampleContent.workflow("delete-vm"), input, SampleContent.GONZALO).block();

        assertThat(step(gone, "delete").effect().json()).isEqualTo("none");
        assertThat(step(gone, "delete").detail()).startsWith("vsphere vm.delete (destructive) · plan vm.get: nothing to delete");
        assertThat(gone.destructive()).isFalse();
    }

    @Test
    void previewListsWhatWasBuiltFromAResourceTheRunChanges() {
        when(w.resources.lineage("vm-1042")).thenReturn(Mono.just(new Lineage(RunsTestWiring.resource("vm", "vm-1042", "ready"), List.of(),
                List.of(RunsTestWiring.resource("snapshot", "snap-1", "ready")))));
        when(w.resources.lineage("snap-1")).thenReturn(Mono.just(new Lineage(RunsTestWiring.resource("snapshot", "snap-1", "ready"), List.of(),
                List.of(RunsTestWiring.resource("volume", "vol-7", "ready")))));
        when(w.resources.lineage("img-77")).thenReturn(Mono.just(new Lineage(RunsTestWiring.resource("image", "img-77", "ready"), List.of(),
                List.of(RunsTestWiring.resource("vm", "vm-5", "ready")))));

        Preview restart = handler.preview(SampleContent.workflow("vm-restart"), SampleContent.json(Map.of("vmId", "vm-1042")), SampleContent.GONZALO).block();

        assertThat(restart.affectedResources()).extracting(AffectedResource::id, AffectedResource::name, AffectedResource::via).containsExactly(
                tuple("vm-1042", "name-of-vm-1042", "input.vmId"),
                tuple("snap-1", "name-of-snap-1", "built from vm-1042"),
                tuple("vol-7", "name-of-vol-7", "built from snap-1"));

        Preview create = handler.preview(SampleContent.workflow("create-vm"), SampleContent.json(Map.of("name", "web-01", "image", "img-77")),
                SampleContent.GONZALO).block();
        assertThat(create.affectedResources()).as("building on an image changes nothing built from it").extracting(AffectedResource::id)
                .containsExactly("img-77");
    }

    @Test
    void previewOfARequestToBuildShowsTheBuildAndTheDryRunReadsBackWhatItBuilt() {
        // the registry does not know the image the dry run builds
        when(w.resources.use(eq("image"), startsWith("img-"), any(), anyBoolean())).thenReturn(Mono.empty());
        JsonNode input = SampleContent.json(Map.of("name", "web-01", "image", Map.of("create", Map.of("os", "rhel-9"))));

        Preview p = handler.preview(SampleContent.workflow("create-vm"), input, SampleContent.GONZALO).block();

        assertThat(p.steps()).extracting(PreviewStep::id).startsWith("image.create", "image.create/imageName", "image.create/startBuild");
        assertThat(step(p, "image.create").effect().json()).isEqualTo("create");
        assertThat(step(p, "image.create").detail()).isEqualTo("run build-vm-image");
        assertThat(step(p, "useImage").skipped()).isFalse();
        assertThat(p.affectedResources()).as("what the dry run built is no resource yet").isEmpty();
        assertThat(p.cost().monthly()).isEqualTo(38.5);

        DryRunResult dry = handler.dryRun(SampleContent.workflow("create-vm"), input, SampleContent.GONZALO).block();
        assertThat(dry.passed()).as(String.valueOf(dry.error())).isTrue();
        assertThat(dry.steps()).filteredOn(s -> s.id().equals("useImage")).singleElement()
                .satisfies(s -> assertThat(s.output().path("dryRun").asBoolean()).isTrue());
    }

    @Test
    void previewKeepsTheStaticWalkPastAStepTheDryRunCannotPass() {
        when(w.resources.use(eq("vm"), eq("vm-404"), any(), anyBoolean())).thenReturn(Mono.empty());

        Preview p = handler.preview(SampleContent.workflow("delete-vm"), SampleContent.json(Map.of("vmId", "vm-404", "reason", "x")), SampleContent.GONZALO).block();

        assertThat(p.steps()).noneMatch(PreviewStep::skipped);
        assertThat(step(p, "delete").effect().json()).isEqualTo("delete");
        assertThat(step(p, "delete").detail()).as("no plan past the failure").isEqualTo("vsphere vm.delete (destructive)");
        assertThat(p.cost().detail()).as("the snapshot it never reached, priced from the flow").isEqualTo("vsphere vm.snapshot 4.20 (price list list-prices)");
    }

    @Test
    void previewPricesEveryItemOfALoopAndJudgesATerraformApplyByItsPlan() {
        Preview p = handler.preview(SampleContent.workflow("web-environment"),
                SampleContent.json(Map.of("name", "shop", "environment", "dev", "vmCount", 3, "image", "img-77")), SampleContent.GONZALO).block();

        assertThat(p.cost().monthly()).isEqualTo(172.5);
        assertThat(p.cost().detail()).isEqualTo("mongodb cluster.create 57.00 (price list list-prices) + vsphere vm.create 38.50 x 3 (price list list-prices)");
        assertThat(step(p, "loadBalancer").detail()).endsWith(" · plan: 3 to add, 0 to change, 0 to destroy");
        assertThat(step(p, "database/approval").skipped()).as("a dev database needs no approval").isTrue();
    }

    @Test
    void aBatchPreviewWalksTheFirstRowAndPricesEveryRowLikeIt() {
        List<JsonNode> items = List.of(SampleContent.json(Map.of("name", "web-01", "image", "img-1", "size", "medium")),
                SampleContent.json(Map.of("name", "web-02", "image", "img-2", "size", "medium")));

        CommandAccepted acc = handler.startBatch(new StartBatch("create-vm", null, items, null, null, SampleContent.GONZALO, "batch", null)).block();

        Preview p = w.projection.get(acc.id()).orElseThrow().preview();
        assertThat(p.cost().monthly()).isEqualTo(154.0);
        assertThat(p.cost().detail()).isEqualTo("vsphere vm.create 77.00 (price list list-prices) x 2 rows");
        assertThat(p.affectedResources()).extracting(AffectedResource::id).containsExactly("img-1", "img-2");
    }

    // ---------------------------------------------------------------- triggers

    @Test
    void alertTriggersStartEveryMatchingPublishedWorkflow() {
        JsonNode alert = SampleContent.json(Map.of(
                "source", "grafana",
                "labels", Map.of("alertname", "VMUnresponsive", "severity", "critical", "vm_id", "vm-2001"),
                "annotations", Map.of("summary", "VM vm-2001 stopped answering")));

        TriggerResult r = handler.triggerAlert(alert).contextWrite(c -> CurrentIdentity.with(c, SampleContent.GONZALO)).block();

        assertThat(r.started()).singleElement().satisfies(s -> assertThat(s.workflow()).isEqualTo("vm-restart"));
        Job job = w.projection.get(r.started().get(0).jobId()).orElseThrow();
        assertThat(job.channel()).isEqualTo("trigger");
        assertThat(job.input().path("vmId").asText()).isEqualTo("vm-2001");
        assertThat(job.input().path("reason").asText()).isEqualTo("VM vm-2001 stopped answering");

        TriggerResult none = handler.triggerAlert(SampleContent.json(Map.of("source", "grafana", "labels", Map.of("alertname", "DiskFull")))).block();
        assertThat(none.started()).isEmpty();

        TriggerResult vuln = handler.triggerAlert(SampleContent.json(Map.of("source", "vuln-scanner",
                "labels", Map.of("severity", "critical", "vm_id", "vm-1042", "cve", "CVE-2026-1234")))).block();
        assertThat(vuln.started()).singleElement().satisfies(s -> assertThat(s.workflow()).isEqualTo("patch-cve"));
    }

    @Test
    void aMatchTheCallerMayNotInvokeIsReportedAsSkipped() {
        JsonNode alert = SampleContent.json(Map.of("source", "grafana",
                "labels", Map.of("alertname", "VMUnresponsive", "severity", "critical", "vm_id", "vm-2001"), "annotations", Map.of("summary", "s")));
        TriggerResult r = handler.triggerAlert(alert).contextWrite(c -> CurrentIdentity.with(c, NOBODY)).block();
        assertThat(r.started()).isEmpty();
        assertThat(r.skipped()).singleElement().satisfies(s -> assertThat(s.workflow()).isEqualTo("vm-restart"));
    }
}
