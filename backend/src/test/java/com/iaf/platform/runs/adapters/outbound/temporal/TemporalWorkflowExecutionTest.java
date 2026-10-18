package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowExecutionDescription;

/** The port over Temporal's client: start, signals, cancel and "running" against the test server. */
class TemporalWorkflowExecutionTest {

    private static final WorkflowDefinition WAIT = WorkflowYaml.parse("""
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-wait, title: Wait, noun: thing, verb: wait, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - pause:
                    name: Pause
                    wait: { duration: 1h }
            """);

    private TemporalTestEngine t;

    @BeforeEach
    void setUp() {
        t = new TemporalTestEngine();
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    @Test
    void startRunsTheJobAsAWorkflowWhoseIdIsTheJobId() {
        Job job = t.start(WAIT, Map.of());

        WorkflowExecutionDescription d = t.client.newUntypedWorkflowStub(job.id()).describe();
        assertThat(d.getExecution().getWorkflowId()).isEqualTo(job.id());
        assertThat(d.getWorkflowType()).isEqualTo("InterpreterWorkflow");
        assertThat(d.getTaskQueue()).isEqualTo(TemporalTestEngine.QUEUE);
        assertThat(d.getStatus()).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        assertThat(t.execution.isRunning(job.id()).block()).isTrue();
        RunsTestWiring.await(TemporalTestEngine.WAIT, () -> t.job(job.id()).step("pause") != null);
        assertThat(t.workflow(job.id()).status()).isEqualTo(JobStatus.RUNNING);

        // nothing to signal: the hour passes on the test server's clock
        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.execution.isRunning(job.id()).block()).isFalse();
        assertThat(t.job(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
    }

    @Test
    void aJobIdRunsOnlyOnce() {
        Job job = t.start(WAIT, Map.of());

        assertThatThrownBy(() -> t.execution.start(job).block()).isInstanceOf(ConflictException.class).hasMessageContaining("already running");
        t.execution.cancel(job.id(), "gonzalo").block();
        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThatThrownBy(() -> t.execution.start(job).block()).isInstanceOf(ConflictException.class);
    }

    @Test
    void aBatchThatEndedCanBeStartedAgainToRetryItsRows() {
        WorkflowDefinition def = SampleContent.workflow("request-storage");
        RolloutPolicy rollout = new RolloutPolicy(List.of(100), 2, 0);
        Job batch = JobFactory.newBatch(def, def.version(), List.of(SampleContent.json(Map.of("vmId", "vm-1", "sizeGb", 100))), null, rollout,
                WaveScheduler.plan(1, rollout), SampleContent.GONZALO, "batch", null, null, null, t.w.clock.instant());
        Job single = JobFactory.newJob(def, def.version(), null, SampleContent.GONZALO, "rest", null, null, null, null, null, t.w.clock.instant());
        assertThat(TemporalWorkflowExecution.reusePolicy(batch)).isEqualTo(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
        assertThat(TemporalWorkflowExecution.reusePolicy(single)).isEqualTo(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE);

        t.start(batch);
        assertThatThrownBy(() -> t.execution.start(batch).block()).isInstanceOf(ConflictException.class).hasMessageContaining("already running");
        assertThat(t.result(batch.id()).status()).isEqualTo(JobStatus.SUCCEEDED);

        t.execution.start(batch).block();
        assertThat(t.result(batch.id()).status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.w.projection.children(batch.id())).hasSize(2);
    }

    @Test
    void anApprovalSignalReachesTheJob() {
        Job job = t.start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042"));
        t.awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        Approval a = t.job(job.id()).pendingApproval("approval");
        // a decision for another approval is kept aside and does not wake the job
        t.execution.signalApproval(job.id(), "apr_other", ApprovalDecision.approved("gonzalo", null, Instant.now())).block();
        t.awaitWorkflowStatus(job.id(), JobStatus.WAITING_APPROVAL);

        ApprovalDecision no = ApprovalDecision.rejected("ana", "not today", Instant.now());
        t.w.events.approvalDecided(a, no);
        t.execution.signalApproval(job.id(), a.id(), no).block();

        Outcome outcome = t.result(job.id());
        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("rejected");
        assertThat(outcome.message()).isEqualTo("Rejected by ana: not today");
        Job failed = t.job(job.id());
        assertThat(failed.failedStep()).isEqualTo("approval");
        assertThat(failed.step("notifyOwner").status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(failed.step("notifyOwner").output().path("message").asText()).contains("failed at approval");
    }

    @Test
    void anAttentionDecisionSentBeforeThePauseIsKeptForIt() {
        t.close();
        t = new TemporalTestEngine(calls -> TemporalTestEngine.failing(calls, "vm.get", 1));
        Job job = t.start(SampleContent.workflow("vm-restart"), Map.of("vmId", "vm-1042"));
        t.execution.signalAttention(job.id(), AttentionDecision.retry("ana")).block();

        t.awaitStatus(job.id(), JobStatus.WAITING_APPROVAL);
        assertThat(t.event(job.id(), "job.attention-resolved").data().path("action").asText()).isEqualTo("retry");
        assertThat(t.job(job.id()).step("checkVmExists").status()).isEqualTo(StepStatus.SUCCEEDED);
        t.execution.cancel(job.id(), "gonzalo").block();
        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void aJobWithoutAnExecutionIsNotRunningAndItsSignalsConflictAsInMemory() {
        ApprovalDecision go = ApprovalDecision.approved("gonzalo", null, Instant.now());
        assertThatThrownBy(() -> t.execution.signalApproval("job_nope", "apr_1", go).block())
                .isInstanceOf(ConflictException.class).hasMessage("Job job_nope is not running");
        assertThatThrownBy(() -> t.execution.signalAttention("job_nope", AttentionDecision.skip("ana")).block())
                .isInstanceOf(ConflictException.class).hasMessage("Job job_nope is not running");
        assertThatThrownBy(() -> t.execution.cancel("job_nope", "gonzalo").block()).isInstanceOf(ConflictException.class);
        assertThat(t.execution.isRunning("job_nope").block()).isFalse();

        RunsTestWiring memory = new RunsTestWiring();
        assertThatThrownBy(() -> memory.execution.signalApproval("job_nope", "apr_1", go).block())
                .isInstanceOf(ConflictException.class).hasMessage("Job job_nope is not running");
        assertThatThrownBy(() -> memory.execution.signalAttention("job_nope", AttentionDecision.skip("ana")).block())
                .isInstanceOf(ConflictException.class).hasMessage("Job job_nope is not running");
    }

    @Test
    void aJobThatEndedTakesNoMoreSignals() {
        Job job = t.start(WAIT, Map.of());
        t.execution.cancel(job.id(), "gonzalo").block();
        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);

        assertThat(t.execution.isRunning(job.id()).block()).isFalse();
        assertThatThrownBy(() -> t.execution.cancel(job.id(), "gonzalo").block()).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.execution.signalAttention(job.id(), AttentionDecision.skip("ana")).block())
                .isInstanceOf(ConflictException.class).hasMessageContaining("is not running");
    }

    @Test
    void aJobLinksToItsExecutionInTheTemporalUiOnceAUiUrlIsSet() {
        assertThat(t.execution.links("job_1")).as("no ui-url, no link").isEmpty();

        PlatformProperties.Temporal temporal = new PlatformProperties.Temporal();
        temporal.setUiUrl("http://localhost:8233/");
        assertThat(TemporalWorkflowExecution.temporalLink(temporal, "job_1")).contains("http://localhost:8233/namespaces/default/workflows/job_1");

        temporal.setUiUrl(" https://temporal.example.internal/ui ");
        temporal.setNamespace("team a");
        assertThat(TemporalWorkflowExecution.temporalLink(temporal, "job 2/x"))
                .contains("https://temporal.example.internal/ui/namespaces/team%20a/workflows/job%202%2Fx");

        temporal.setUiUrl("  ");
        assertThat(TemporalWorkflowExecution.temporalLink(temporal, "job_1")).isEmpty();
        temporal.setUiUrl(null);
        assertThat(TemporalWorkflowExecution.temporalLink(temporal, "job_1")).isEmpty();
    }

    @Test
    void theEngineGivesTheLinkUnderTheTemporalName() {
        try (TemporalTestEngine linked = new TemporalTestEngine(calls -> calls, c -> c.setUiUrl("http://localhost:8233"), env -> { })) {
            Job job = linked.start(WAIT, Map.of());
            assertThat(linked.execution.links(job.id()))
                    .containsExactly(Map.entry("temporal", "http://localhost:8233/namespaces/default/workflows/" + job.id()));
            linked.execution.cancel(job.id(), "gonzalo").block();
        }
    }
}
