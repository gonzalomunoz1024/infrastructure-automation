package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.events.DomainEvent;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.FlakyProjectionStore;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.adapters.outbound.ChangeProperties;
import com.iaf.platform.runs.adapters.outbound.DemoRunsSeeder;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowExecutionDescription;
import io.temporal.client.WorkflowFailedException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.failure.CanceledFailure;
import reactor.core.publisher.Mono;

/**
 * The interpreter as a Temporal workflow on the test server: the same scenarios as the in-memory
 * engine's tests, with signals for the people, child workflows for {@code run} steps and batch
 * rows, and skipped time for the waits.
 */
class InterpreterWorkflowImplTest {

    private static final String ATTENTION = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-attention, title: Attention, noun: thing, verb: poke, owner: team-x, version: 1.0.0 }
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
            """;

    private static final String FLAKY = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-flaky, title: Flaky, noun: thing, verb: poke, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                - after:
                    name: After
                    notify: { channel: "#x", message: "after {{ $('Get').json.name }}" }
            """;

    private static final String COMPENSATE = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-compensate, title: Compensate, noun: thing, verb: poke, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                    compensate:
                      - undo:
                          name: Undo
                          notify: { channel: "#x", message: "undo {{ $json.name }}" }
                - broken:
                    name: Broken
                    call: provider
                    with: { provider: vsphere, action: vm.doesNotExist, input: { id: "{{ $input.id }}" } }
            """;

    private static final String WAIT = """
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
                    notify: { channel: "#x", message: "after the wait" }
            """;

    private static final String PARENT = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-parent, title: Parent, noun: thing, verb: run, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - child:
                    name: Child
                    run: { workflow: t-wait, version: "^1" }
                - after:
                    name: After
                    notify: { channel: "#x", message: "never" }
            """;

    private static final String BATCH = """
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
            """;

    /** A child with an input schema (one required field, one default). */
    private static final String QUICK = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-quick, title: Quick, noun: thing, verb: do, owner: team-x, version: 1.0.0 }
            api:
              method: POST
              path: "/api/v1/things:do"
              input: { type: object, required: [id], properties: { id: { type: string }, note: { type: string, default: none } } }
            workflow:
              do:
                - say:
                    name: Say
                    notify: { channel: "#x", message: "{{ $input.id }} {{ $input.note }}" }
            """;

    /** A child only one team may invoke. */
    private static final String SECRET = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-secret, title: Secret, noun: thing, verb: hide, owner: team-x, version: 1.0.0 }
            policy:
              rbac: [team-secret]
            workflow:
              do:
                - say:
                    name: Say
                    notify: { channel: "#x", message: "hidden" }
            """;

    /** A child whose one step fails outright. */
    private static final String BROKEN = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-broken, title: Broken, noun: thing, verb: break, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - check:
                    name: Check
                    notify: { channel: "#x", message: "checking" }
                    assert: "{{ $json.message == 'never' }}"
                    pauseOnFailure: false
            """;

    /** A parent of one run step ({@code run} is the step's own line) that fails the job outright, then a notify. */
    private static String parent(String run) {
        return """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-parent, title: Parent, noun: thing, verb: run, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - child:
                        name: Child
                        run: %s
                        pauseOnFailure: false
                    - after:
                        name: After
                        notify: { channel: "#x", message: "after the child" }
                """.formatted(run);
    }

    private static WorkflowExecutionHistory history(String resource) throws IOException {
        try (InputStream in = InterpreterWorkflowImplTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return WorkflowExecutionHistory.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> activities(WorkflowExecutionHistory history) {
        return history.getEvents().stream().filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
                .map(e -> e.getActivityTaskScheduledEventAttributes().getActivityType().getName()).toList();
    }

    private TemporalTestEngine t;

    @AfterEach
    void tearDown() {
        if (t != null) {
            t.close();
        }
    }

    private static WorkflowDefinition yaml(String yaml) {
        return WorkflowYaml.parse(yaml);
    }

    private void approve(Job job, Approval a) {
        ApprovalDecision go = ApprovalDecision.approved("gonzalo", "go", Instant.now());
        // the command handler records the decision, then signals the engine
        t.w.events.approvalDecided(a, go);
        t.execution.signalApproval(job.id(), a.id(), go).block();
    }

    @Test
    void aJobWaitsForTheApprovalSignalThenFinishes() {
        t = new TemporalTestEngine();
        Job job = t.start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042", "reason", "stuck", "environment", "dev"));

        t.awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        Job waiting = t.job(job.id());
        Approval a = waiting.pendingApproval("approval");
        assertThat(a).isNotNull();
        assertThat(a.group()).isEqualTo("compute-approvers");
        assertThat(a.message()).isEqualTo("Restart web-1042 (poweredOn) in dev? Reason: stuck");
        assertThat(waiting.step("checkVmExists").status()).isEqualTo(StepStatus.SUCCEEDED);
        t.awaitWorkflowStatus(job.id(), JobStatus.WAITING_APPROVAL);
        assertThat(t.workflow(job.id()).waitingOn()).isEqualTo(a.id());
        assertThat(t.job(job.id()).step("approval").status()).isEqualTo(StepStatus.WAITING);
        assertThat(t.execution.isRunning(job.id()).block()).isTrue();
        assertThat(t.types(job.id())).startsWith("job.started", "job.step", "job.step", "job.step", "job.step", "job.step", "job.approval-requested");

        approve(job, a);

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(outcome.output().path("vmName").asText()).isEqualTo("web-1042");
        Job done = t.job(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.output().path("vmName").asText()).isEqualTo("web-1042");
        assertThat(done.output().path("powerState").asText()).isEqualTo("poweredOn");
        assertThat(done.steps()).extracting(JobStep::id).containsExactly("checkVmExists", "noBackupRunning", "approval", "restart", "validate", "notifyDone");
        assertThat(done.steps()).extracting(JobStep::status).containsOnly(StepStatus.SUCCEEDED);
        assertThat(done.step("approval").output().path("by").asText()).isEqualTo("gonzalo");
        assertThat(done.step("restart").durationMs()).isNotNull();
        assertThat(done.endedAt()).isNotNull();
        assertThat(t.steps(job.id())).containsExactly(
                "checkVmExists:running", "checkVmExists:succeeded",
                "noBackupRunning:running", "noBackupRunning:succeeded",
                "approval:running", "approval:waiting", "approval:succeeded",
                "restart:running", "restart:succeeded",
                "validate:running", "validate:succeeded",
                "notifyDone:running", "notifyDone:succeeded");
        assertThat(t.types(job.id())).endsWith("job.finished").containsOnlyOnce("job.finished");
        assertThat(t.workflow(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.execution.isRunning(job.id()).block()).isFalse();
        verify(t.w.telemetry, atLeastOnce()).record(any());
        t.replay(job.id());
    }

    @Test
    void anApprovalNobodyDecidesEscalatesThenExpiresOnTheWorkflowClock() {
        t = new TemporalTestEngine();
        Job job = t.start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042"));
        t.awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        long before = t.env.currentTimeMillis();

        // nobody signals: blocking on the result lets the test server skip the 2h escalation and the 4h timeout
        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("expired");
        assertThat(outcome.failedStep()).isEqualTo("approval");
        assertThat(t.env.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofHours(4).toMillis());
        Job failed = t.job(job.id());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.errorClass()).isEqualTo("expired");
        assertThat(failed.approvals()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(Approval.Status.EXPIRED));
        assertThat(t.event(job.id(), "job.approval-escalated").data().path("to").asText()).isEqualTo("compute-leads");
        assertThat(failed.step("notifyOwner").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(failed.step("restart")).isNull();
        t.replay(job.id());
    }

    @Test
    void aFailedStepPausesForAttentionAndARetryRunsItAgain() {
        t = new TemporalTestEngine(calls -> TemporalTestEngine.failing(calls, "vm.get", 1));
        Job job = t.start(yaml(FLAKY), Map.of("id", "vm-1042"));

        t.awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        Job paused = t.job(job.id());
        assertThat(paused.attention().step()).isEqualTo("get");
        assertThat(paused.attention().error().errorClass()).isEqualTo("unavailable");
        assertThat(paused.attention().options()).containsExactly("retry", "edit-input", "skip", "abort");
        assertThat(paused.step("get").status()).isEqualTo(StepStatus.FAILED);
        t.awaitWorkflowStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        assertThat(t.workflow(job.id()).waitingOn()).isEqualTo("get");

        t.execution.signalAttention(job.id(), AttentionDecision.retry("ana")).block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job done = t.job(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(done.attention()).isNull();
        assertThat(done.step("get").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(done.step("after").output().path("message").asText()).isEqualTo("after web-1042");
        assertThat(t.steps(job.id())).containsSubsequence("get:running", "get:failed", "get:running", "get:succeeded", "after:succeeded");
        assertThat(t.events(job.id(), "job.attention")).hasSize(1);
        DomainEvent resolved = t.event(job.id(), "job.attention-resolved");
        assertThat(resolved.data().path("action").asText()).isEqualTo("retry");
        assertThat(resolved.data().path("by").asText()).isEqualTo("ana");
        t.replay(job.id());
    }

    @Test
    void anEditInputDecisionPublishesTheEditedInputForTheAudit() {
        t = new TemporalTestEngine(calls -> TemporalTestEngine.failing(calls, "vm.get", 1));
        Job job = t.start(yaml(FLAKY), Map.of("id", "vm-1042"));
        t.awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        t.awaitWorkflowStatus(job.id(), JobStatus.NEEDS_ATTENTION);

        t.execution.signalAttention(job.id(), AttentionDecision.editInput(SampleContent.json(Map.of("input", Map.of("id", "vm-2001"))), "ana")).block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        DomainEvent resolved = t.event(job.id(), "job.attention-resolved");
        assertThat(resolved.data().path("action").asText()).isEqualTo("edit-input");
        assertThat(resolved.data().path("by").asText()).isEqualTo("ana");
        assertThat(resolved.data().at("/input/input/id").asText()).isEqualTo("vm-2001");
        assertThat(t.job(job.id()).step("after").output().path("message").asText()).isEqualTo("after web-2001");
        t.replay(job.id());
    }

    @Test
    void skipLeavesTheFailedStepBehindAndContinues() {
        t = new TemporalTestEngine();
        Job job = t.start(yaml(ATTENTION), Map.of("id", "vm-1042"));

        t.awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        Job paused = t.job(job.id());
        assertThat(paused.attention().step()).isEqualTo("broken");
        assertThat(paused.attention().error().errorClass()).isEqualTo("unknown_action");

        t.execution.signalAttention(job.id(), AttentionDecision.skip("ana")).block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job done = t.job(job.id());
        assertThat(done.attention()).isNull();
        assertThat(done.step("broken").status()).isEqualTo(StepStatus.SKIPPED);
        assertThat(done.step("after").output().path("message").asText()).isEqualTo("after web-1042");
        assertThat(t.event(job.id(), "job.attention-resolved").data().path("action").asText()).isEqualTo("skip");
        t.replay(job.id());
    }

    @Test
    void abortRollsBackTheCompletedStepsWithTheirCompensation() {
        t = new TemporalTestEngine();
        Job job = t.start(yaml(COMPENSATE), Map.of("id", "vm-1042"));

        t.awaitStatus(job.id(), JobStatus.NEEDS_ATTENTION);
        t.execution.signalAttention(job.id(), AttentionDecision.abort("ana")).block();

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.ROLLED_BACK);
        assertThat(outcome.failedStep()).isEqualTo("broken");
        Job done = t.job(job.id());
        assertThat(done.status()).isEqualTo(JobStatus.ROLLED_BACK);
        assertThat(done.step("get").status()).isEqualTo(StepStatus.COMPENSATED);
        assertThat(done.step("undo").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(done.step("undo").output().path("message").asText()).isEqualTo("undo web-1042");
        assertThat(t.steps(job.id())).containsSubsequence("broken:failed", "undo:succeeded", "get:compensated");
        assertThat(t.event(job.id(), "job.attention-resolved").data().path("action").asText()).isEqualTo("abort");
        t.replay(job.id());
    }

    @Test
    void aCancelSignalWakesAWaitingJob() {
        t = new TemporalTestEngine();
        Job job = t.start(yaml(WAIT), Map.of());
        RunsTestWiring.await(TemporalTestEngine.WAIT, () -> t.job(job.id()).step("pause") != null);
        assertThat(t.execution.isRunning(job.id()).block()).isTrue();

        t.execution.cancel(job.id(), "mateo").block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        Job cancelled = t.job(job.id());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.step("after")).isNull();
        assertThat(cancelled.endedAt()).isNotNull();
        assertThat(t.event(job.id(), "job.cancelled").data().path("by").asText()).isEqualTo("mateo");
        assertThat(t.types(job.id())).doesNotContain("job.finished");
        assertThat(t.execution.isRunning(job.id()).block()).isFalse();
        t.replay(job.id());
    }

    @Test
    void aCancelRequestedThroughTemporalEndsTheJobCancelledByTemporal() {
        t = new TemporalTestEngine();
        Job job = t.start(yaml(WAIT), Map.of());
        RunsTestWiring.await(TemporalTestEngine.WAIT, () -> t.job(job.id()).step("pause") != null);

        // the Temporal UI or CLI, not the platform's API
        t.client.newUntypedWorkflowStub(job.id()).cancel();

        // the SDK closes a workflow whose cancel was requested as cancelled, whatever it returns
        assertThatThrownBy(() -> t.result(job.id())).isInstanceOf(WorkflowFailedException.class).hasCauseInstanceOf(CanceledFailure.class);
        RunsTestWiring.await(TemporalTestEngine.WAIT, () -> !t.execution.isRunning(job.id()).block());
        Job cancelled = t.job(job.id());
        assertThat(cancelled.status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(cancelled.step("after")).isNull();
        assertThat(t.event(job.id(), "job.cancelled").data().path("by").asText()).isEqualTo("temporal");
        t.replay(job.id());
    }

    @Test
    void aCancelReachesTheChildWorkflowsOfTheJob() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-wait"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(WAIT))));
        Job job = t.start(yaml(PARENT), Map.of());
        RunsTestWiring.await(TemporalTestEngine.WAIT,
                () -> t.w.projection.children(job.id()).stream().anyMatch(c -> c.step("pause") != null));
        Job child = t.w.projection.children(job.id()).get(0);
        assertThat(t.execution.isRunning(child.id()).block()).isTrue();

        t.execution.cancel(job.id(), "mateo").block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(t.result(child.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(t.job(child.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(t.job(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(t.job(job.id()).step("after")).isNull();
        assertThat(t.event(child.id(), "job.cancelled").data().path("by").asText()).isEqualTo("mateo");
        assertThat(t.event(job.id(), "job.cancelled").data().path("by").asText()).isEqualTo("mateo");
        t.replay(job.id());
        t.replay(child.id());
    }

    @Test
    void aChildCancelledThroughTemporalFailsTheStepThatRanIt() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-wait"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(WAIT))));
        Job job = t.start(yaml(PARENT), Map.of());
        RunsTestWiring.await(TemporalTestEngine.WAIT,
                () -> t.w.projection.children(job.id()).stream().anyMatch(c -> c.step("pause") != null));
        Job child = t.w.projection.children(job.id()).get(0);

        t.client.newUntypedWorkflowStub(child.id()).cancel();

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("cancelled");
        assertThat(outcome.failedStep()).isEqualTo("child");
        assertThat(t.job(child.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(t.event(child.id(), "job.cancelled").data().path("by").asText()).isEqualTo("temporal");
        Job failed = t.job(job.id());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.attention()).isNull();
        assertThat(failed.step("after")).isNull();
        t.replay(job.id());
    }

    @Test
    void aChildWorkflowHandsItsRegistrationsBackSoAnInputAskingForANewResourceIsBuiltFirst() {
        t = new TemporalTestEngine();
        Job job = t.start(SampleContent.workflow("create-vm"), Map.of("name", "web-01", "image", Map.of("create", Map.of("os", "rhel-9")), "size", "small"));

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.w.projection.children(job.id())).singleElement().satisfies(child -> {
            assertThat(child.workflow()).isEqualTo("build-vm-image");
            assertThat(child.status()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(child.traceId()).isEqualTo(job.traceId());
            assertThat(child.input().path("hardening").asText()).as("the child starts with its schema's defaults, as its own route would fill them").isEqualTo("cis-l1");
            assertThat(child.step("registerBuilding").input().path("attributes").path("hardening").asText()).isEqualTo("cis-l1");
            assertThat(t.result(child.id()).registered()).as("the child workflow's result carries what it registered (building, then ready)")
                    .isNotEmpty().allSatisfy(r -> assertThat(r.type()).isEqualTo("image"))
                    .extracting(JobContext.Registered::id).containsOnly(t.result(child.id()).registered().get(0).id());
        });
        Job done = t.job(job.id());
        JobStep build = done.step("image.create");
        assertThat(build.status()).isEqualTo(StepStatus.SUCCEEDED);
        String imageId = build.output().path("resources").get(0).path("id").asText();
        assertThat(imageId).isNotBlank();
        assertThat(build.output().path("resources").get(0).path("type").asText()).isEqualTo("image");
        assertThat(done.step("useImage").input().path("id").asText()).as("the job goes on with the new id").isEqualTo(imageId);
        t.replay(job.id());
    }

    @Test
    void aChildIsRecordedByItsOwnFirstActivityNotByTheParentBeforeItStarts() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-quick"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(QUICK))));
        Job job = t.start(yaml(parent("{ workflow: t-quick, version: \"^1\", input: { id: thing-1 } }")), Map.of());

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job child = t.w.projection.children(job.id()).get(0);
        assertThat(child.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(child.input().path("note").asText()).as("the child's own defaults").isEqualTo("none");
        assertThat(child.step("say").output().path("message").asText()).isEqualTo("thing-1 none");
        assertThat(t.types(child.id())).startsWith("job.started").endsWith("job.finished");
        assertThat(activities(t.client.fetchHistory(child.id()))).as("the child records itself first").startsWith("Started");
        assertThat(activities(t.client.fetchHistory(job.id()))).as("the parent only resolves and checks it").contains("ChildJob").doesNotContain("Started");
        t.replay(job.id());
        t.replay(child.id());
    }

    @Test
    void historiesRecordedWhileTheParentStoredItsChildStillReplay() throws IOException {
        t = new TemporalTestEngine();
        WorkflowExecutionHistory parent = history("/temporal/before-child-records-itself/run-step-parent.json");
        WorkflowExecutionHistory child = history("/temporal/before-child-records-itself/run-step-child.json");
        assertThat(activities(parent)).contains("ChildJob");
        assertThat(activities(child)).as("recorded before children recorded themselves").doesNotContain("Started");

        t.replay(parent);
        t.replay(child);
    }

    @Test
    void aRunStepWithoutAVersionRunsTheChildsCurrentMajorAndSaysSo() {
        t = new TemporalTestEngine();
        when(t.w.workflows.published("t-quick")).thenReturn(Mono.just(RunsTestWiring.record(yaml(QUICK))));
        when(t.w.workflows.resolve(eq("t-quick"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(QUICK))));
        Job job = t.start(yaml(parent("{ workflow: t-quick, input: { id: thing-1 } }")), Map.of());

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        verify(t.w.workflows).resolve("t-quick", "^1");
        assertThat(t.events(job.id(), "job.log")).extracting(e -> e.data().path("message").asText())
                .contains("child: no version given, running t-quick ^1 (its current major)");
        assertThat(t.w.projection.children(job.id())).singleElement().satisfies(c -> assertThat(c.status()).isEqualTo(JobStatus.SUCCEEDED));
        t.replay(job.id());
    }

    @Test
    void aChildTheRequesterMayNotInvokeFailsTheParentStepAsForbiddenAndNeverStarts() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-secret"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(SECRET))));
        WorkflowDefinition def = yaml(parent("{ workflow: t-secret, version: \"^1\" }"));
        Job job = t.start(JobFactory.newJob(def, def.version(), SampleContent.json(Map.of()), SampleContent.ANA, "rest", null, null, null, null, null,
                t.w.clock.instant()));

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("forbidden");
        assertThat(outcome.failedStep()).isEqualTo("child");
        assertThat(outcome.message()).isEqualTo("Ana may not invoke t-secret (needs one of [team-secret])");
        Job failed = t.job(job.id());
        assertThat(failed.step("child").error().errorClass()).isEqualTo("forbidden");
        assertThat(failed.step("after")).isNull();
        assertThat(t.w.projection.children(job.id())).isEmpty();
        assertThat(activities(t.client.fetchHistory(job.id()))).doesNotContain("Started");
        t.replay(job.id());
    }

    @Test
    void aChildThePolicyEngineDeniesFailsTheParentStepAsForbidden() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-wait"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(WAIT))));
        when(t.w.policy.evaluate(argThat(in -> in != null && "t-wait".equals(in.workflow()))))
                .thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("no-waits", "deny", "nothing waits under a parent")), "mock")));
        Job job = t.start(yaml(parent("{ workflow: t-wait, version: \"^1\" }")), Map.of());

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("forbidden");
        assertThat(outcome.message()).isEqualTo("Policy denies running t-wait: nothing waits under a parent");
        ArgumentCaptor<PolicyInput> asked = ArgumentCaptor.forClass(PolicyInput.class);
        verify(t.w.policy, atLeastOnce()).evaluate(asked.capture());
        PolicyInput in = asked.getAllValues().stream().filter(i -> "t-wait".equals(i.workflow())).findFirst().orElseThrow();
        assertThat(in.phase()).isEqualTo(PolicyPort.Phase.INVOKE);
        assertThat(in.who()).isEqualTo(SampleContent.GONZALO);
        assertThat(in.version()).isEqualTo("1.0.0");
        assertThat(in.context().path("parentId").asText()).isEqualTo(job.id());
        assertThat(in.context().path("channel").asText()).isEqualTo("rest");
        assertThat(t.w.projection.children(job.id())).isEmpty();
        t.replay(job.id());
    }

    @Test
    void aChildWhoseInputDoesNotFitItsSchemaFailsTheParentStepNamingTheField() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-quick"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(QUICK))));
        Job job = t.start(yaml(parent("{ workflow: t-quick, version: \"^1\", input: { note: late } }")), Map.of());

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("invalid_input");
        assertThat(outcome.message()).startsWith("The input of t-quick does not fit its schema: ").contains("id");
        assertThat(t.w.projection.children(job.id())).isEmpty();
        t.replay(job.id());
    }

    @Test
    void aRunStepWithOnFailureContinueRecordsTheChildFailureAndGoesOn() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-broken"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(BROKEN))));
        Job job = t.start(yaml(parent("{ workflow: t-broken, version: \"^1\", onFailure: continue }")), Map.of());

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job child = t.w.projection.children(job.id()).get(0);
        assertThat(child.status()).isEqualTo(JobStatus.FAILED);
        assertThat(child.errorClass()).isEqualTo("assertion_failed");
        Job done = t.job(job.id());
        JobStep step = done.step("child");
        assertThat(step.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(step.output().path("jobId").asText()).isEqualTo(child.id());
        assertThat(step.output().path("status").asText()).isEqualTo("failed");
        assertThat(step.output().path("error").path("class").asText()).isEqualTo("assertion_failed");
        assertThat(step.output().path("error").path("step").asText()).as("the child's step that failed").isEqualTo("check");
        assertThat(done.step("after").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(t.events(job.id(), "job.log")).extracting(e -> e.data().path("message").asText())
                .anyMatch(m -> m.contains("continuing (onFailure: continue)"));
        t.replay(job.id());
        t.replay(child.id());
    }

    @Test
    void aRunStepWithoutOnFailureContinueFailsWithTheChildsErrorClass() {
        t = new TemporalTestEngine();
        when(t.w.workflows.resolve(eq("t-broken"), anyString())).thenReturn(Mono.just(RunsTestWiring.record(yaml(BROKEN))));
        Job job = t.start(yaml(parent("{ workflow: t-broken, version: \"^1\" }")), Map.of());

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("assertion_failed");
        assertThat(outcome.failedStep()).isEqualTo("child");
        Job child = t.w.projection.children(job.id()).get(0);
        assertThat(outcome.message()).isEqualTo("Child job " + child.id() + " (t-broken) failed: Assertion failed: {{ $json.message == 'never' }}");
        assertThat(t.job(job.id()).step("after")).isNull();
        t.replay(job.id());
        t.replay(child.id());
    }

    @Test
    void aWaitIsATimerThatTheTestServerSkips() {
        t = new TemporalTestEngine();
        long before = t.env.currentTimeMillis();
        Job job = t.start(yaml(WAIT), Map.of());

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.env.currentTimeMillis() - before).isGreaterThanOrEqualTo(Duration.ofMinutes(10).toMillis());
        Job done = t.job(job.id());
        assertThat(done.step("pause").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(done.step("after").output().path("message").asText()).isEqualTo("after the wait");
        t.replay(job.id());
    }

    @Test
    void aFinalEventTheStoreRefusesIsRetriedUntilTheStoreKeepsItInsteadOfLeavingTheJobRunning() {
        // more refusals than the five attempts an event used to get
        FlakyProjectionStore store = FlakyProjectionStore.refusingTerminalSaves(7);
        t = new TemporalTestEngine(new TemporalTestEngine.Options().wiring(new RunsTestWiring(store)));
        Job job = t.start(yaml(WAIT), Map.of());

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(store.refused()).isEqualTo(7);
        assertThat(t.job(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.types(job.id())).as("a refused event is not published; the one kept is, once").containsOnlyOnce("job.finished");
        assertThat(t.w.projection.history(job.id()).stream().filter(e -> e.terminal()).count()).isOne();
        List<HistoryEvent> attempts = t.client.fetchHistory(job.id()).getEvents().stream()
                .filter(HistoryEvent::hasActivityTaskStartedEventAttributes)
                .filter(e -> e.getActivityTaskStartedEventAttributes().getAttempt() == 8).toList();
        assertThat(attempts).as("the finished activity's eighth attempt kept the event").hasSize(1);
        t.replay(job.id());
    }

    @Test
    void runStepsAreChildWorkflowsWhoseIdsAreTheChildJobs() {
        t = new TemporalTestEngine();
        Job job = t.start(SampleContent.workflow("web-environment"), Map.of("name", "shop", "environment", "dev", "image", "img-1", "vmCount", 2));
        t.awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        approve(job, t.job(job.id()).pendingApproval(null));

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        List<Job> children = t.w.projection.children(job.id());
        assertThat(children).hasSize(3);
        assertThat(children).allMatch(c -> c.status() == JobStatus.SUCCEEDED);
        assertThat(children).extracting(Job::workflow).containsExactlyInAnyOrder("create-vm", "create-vm", "create-database");
        assertThat(children).allMatch(c -> c.traceId().equals(job.traceId()));
        assertThat(children).allMatch(c -> c.channel().equals("rest"));
        for (Job child : children) {
            WorkflowExecutionDescription d = t.client.newUntypedWorkflowStub(child.id()).describe();
            assertThat(d.getStatus()).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED);
            assertThat(d.getParentExecution().getWorkflowId()).isEqualTo(job.id());
        }
        Job done = t.job(job.id());
        assertThat(done.step("build").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(done.step("webServers").items()).isEqualTo(2);
        assertThat(done.step("webServers").output().path("items")).hasSize(2);
        assertThat(done.output().path("url").asText()).isNotBlank();
        assertThat(done.output().path("databaseId").asText()).isNotBlank();
        assertThat(done.step("registerEnv").output().path("type").asText()).isEqualTo("environment");
        t.replay(job.id());
        children.forEach(c -> t.replay(c.id()));
    }

    @Test
    void aBatchRunsItsRowsAsChildWorkflowsInWavesAndStopsWhenTooManyFail() {
        t = new TemporalTestEngine();
        WorkflowDefinition def = yaml(BATCH);
        List<JsonNode> rows = List.of(SampleContent.json(Map.of("id", "vm-0")), SampleContent.json(Map.of("id", "vm-bad")),
                SampleContent.json(Map.of("id", "vm-2")), SampleContent.json(Map.of("id", "vm-3")));
        RolloutPolicy rollout = new RolloutPolicy(List.of(50, 100), 2, 5);
        Job batch = t.start(JobFactory.newBatch(def, "1.0.0", rows, null, rollout, WaveScheduler.plan(rows.size(), rollout), SampleContent.GONZALO, "batch",
                null, null, null, t.w.clock.instant()));

        t.awaitStatus(batch.id(), JobStatus.WAITING_APPROVAL);
        Job waiting = t.job(batch.id());
        assertThat(waiting.approvals()).hasSize(1);
        Approval a = waiting.approvals().get(0);
        assertThat(a.step()).isEqualTo("batch");
        assertThat(a.message()).isEqualTo("Run Batch on 4 rows (2 waves)?");
        approve(batch, a);

        Outcome outcome = t.result(batch.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("stopped");
        Job done = t.job(batch.id());
        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.errorClass()).isEqualTo("stopped");
        assertThat(done.batch().stoppedReason()).contains("stopped after wave 0: 1 of 2 rows failed (more than 5%)");
        assertThat(done.batch().waves()).extracting(Batch.Wave::status).containsExactly("done", "skipped");
        assertThat(done.batch().count("succeeded")).isEqualTo(1);
        assertThat(done.batch().count("failed")).isEqualTo(1);
        assertThat(done.batch().count("skipped")).isEqualTo(2);
        assertThat(done.batch().rows().get(1).error()).contains("assertion_failed");
        assertThat(done.progress()).isEqualTo(new Job.Progress(4, 4, 1));
        assertThat(done.output().path("succeeded").asInt()).isEqualTo(1);
        assertThat(done.output().path("failed").asInt()).isEqualTo(1);
        assertThat(done.output().path("skipped").asInt()).isEqualTo(2);
        List<Job> children = t.w.projection.children(batch.id());
        assertThat(children).hasSize(2);
        assertThat(children).allMatch(c -> c.row() != null && c.channel().equals("batch") && c.preApproved() && c.approvals().isEmpty());
        assertThat(children.stream().filter(c -> c.status() == JobStatus.SUCCEEDED)).singleElement()
                .satisfies(c -> assertThat(c.step("ask").output().path("approved").asBoolean()).isTrue());
        assertThat(t.w.projection.approvals()).hasSize(1);
        assertThat(t.events(batch.id(), "batch.wave")).extracting(e -> e.data().path("status").asText()).containsExactly("running", "done", "skipped");
        assertThat(t.types(batch.id())).endsWith("job.finished");
        t.replay(batch.id());
        children.forEach(c -> t.replay(c.id()));
    }

    @Test
    void seededLiveJobsResumeWhereTheyWait() {
        t = new TemporalTestEngine();
        PlatformProperties props = new PlatformProperties();
        props.getContent().setDir("..");
        DemoRunsSeeder seeder = new DemoRunsSeeder(props, new ChangeProperties(), t.w.workflows, t.w.providers, t.w.resources, t.w.projection, t.w.events, t.w.telemetry,
                t.execution, t.w.clock);
        DemoRunsSeeder.Report report = seeder.seed();
        String waitingId = report.waitingApproval();
        String attentionId = report.needsAttention();
        RunsTestWiring.await(TemporalTestEngine.WAIT, () -> t.execution.isRunning(waitingId).block() && t.execution.isRunning(attentionId).block());

        // the job waiting for its approval waits on the approval it already has
        t.awaitWorkflowStatus(waitingId, JobStatus.WAITING_APPROVAL);
        Approval pending = t.job(waitingId).pendingApproval("approval");
        assertThat(t.workflow(waitingId).waitingOn()).isEqualTo(pending.id());
        approve(t.job(waitingId), pending);
        assertThat(t.result(waitingId).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job upgraded = t.job(waitingId);
        assertThat(upgraded.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(upgraded.approvals()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo(Approval.Status.APPROVED));
        // the steps before the resume point were not published again
        assertThat(t.events(waitingId, "job.step")).noneMatch(e -> e.data().path("replayed").asBoolean());
        assertThat(t.events(waitingId, "job.approval-requested")).hasSize(1);

        // the paused job waits on the attention it already announced
        t.awaitWorkflowStatus(attentionId, JobStatus.NEEDS_ATTENTION);
        assertThat(t.workflow(attentionId).waitingOn()).isEqualTo("validate");
        int announced = t.events(attentionId, "job.attention").size();
        t.execution.signalAttention(attentionId, AttentionDecision.retry("ana")).block();
        assertThat(t.result(attentionId).status()).isEqualTo(JobStatus.SUCCEEDED);
        Job resumed = t.job(attentionId);
        assertThat(resumed.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(resumed.step("validate").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(resumed.output().path("vmName").asText()).isNotBlank();
        assertThat(t.events(attentionId, "job.attention")).hasSize(announced);
        assertThat(t.events(attentionId, "job.step")).noneMatch(e -> e.data().path("replayed").asBoolean());
        t.replay(waitingId);
        t.replay(attentionId);
    }
}
