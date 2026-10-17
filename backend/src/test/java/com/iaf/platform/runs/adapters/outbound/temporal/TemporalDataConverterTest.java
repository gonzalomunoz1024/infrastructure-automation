package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.domain.Approval;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Batch;
import com.iaf.platform.runs.domain.ChangeTicket;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.domain.StepExecutor.RegisterRequest;
import com.iaf.platform.runs.domain.StepExecutor.UseRequest;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.runs.domain.WaveScheduler;
import com.iaf.platform.testing.TestWeb;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;

import io.temporal.api.common.v1.Payload;
import io.temporal.common.converter.DataConverter;

/**
 * Everything that crosses the Temporal boundary (the workflow input, signals, results, activity
 * arguments) must come back equal from the payload converter: the definition snapshot, the
 * {@code JsonNode}s with their nulls and number types, {@code java.time} values, lowercase enums.
 */
class TemporalDataConverterTest {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final Instant T0 = Instant.parse("2026-10-17T10:00:00.123456789Z");

    private final DataConverter converter = TemporalConfig.dataConverter(TestWeb.json());

    @SuppressWarnings("unchecked")
    private <T> T roundTrip(T value) {
        Payload payload = converter.toPayload(value).orElseThrow();
        assertThat(payload.getMetadataOrThrow("encoding").toStringUtf8()).isEqualTo("json/plain");
        return (T) converter.fromPayload(payload, value.getClass(), value.getClass());
    }

    private String json(Object value) {
        return converter.toPayload(value).orElseThrow().getData().toStringUtf8();
    }

    private static ObjectNode input() {
        ObjectNode input = F.objectNode();
        input.put("vmId", "vm-1042");
        input.putNull("reason");
        input.put("count", 3);
        input.put("big", 12_345_678_901L);
        input.put("ratio", 0.25);
        input.put("flag", true);
        input.put("environment", "prod");
        ObjectNode nested = input.putObject("nested");
        nested.putArray("tags").add("a").addNull().add(2);
        nested.putObject("empty");
        return input;
    }

    private static Job richJob() {
        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        Preview preview = new Preview(def.name(), def.version(),
                List.of(new Preview.PreviewStep("restart", "Restart with Ansible", Effect.CHANGE, "restarts the VM")),
                List.of(new Preview.AffectedResource("vm-1042", "vm", "web-1042", "checkVmExists")),
                new Preview.Cost(12.5, "EUR", "per month"),
                new PolicyDecision("needs-approval", List.of(new Rule("prod-approval", "needs-approval", "prod needs a sign-off")), "mock"));
        Approval approval = Approval.builder()
                .id("apr_1")
                .jobId("job_1")
                .workflow(def.name())
                .step("approval")
                .group("compute-approvers")
                .message("Restart?")
                .requestedAt(T0)
                .timeout("4h")
                .status(Approval.Status.PENDING)
                .preview(preview)
                .build();
        JobStep done = JobStep.builder()
                .id("checkVmExists")
                .name("Check VM exists")
                .type(StepType.PROVIDER)
                .status(StepStatus.SUCCEEDED)
                .attempt(1)
                .startedAt(T0)
                .endedAt(T0.plusMillis(40))
                .durationMs(40L)
                .input(SampleContent.json(Map.of("id", "vm-1042")))
                .output(SampleContent.json(Map.of("id", "vm-1042", "name", "web-1042", "cpus", 4)))
                .build();
        JobStep failed = done.toBuilder()
                .id("noBackupRunning")
                .status(StepStatus.FAILED)
                .error(new StepError("assertion_failed", "a backup is running", false))
                .items(2)
                .build();
        return JobFactory.newJob(def, def.version(), input(), SampleContent.GONZALO, "rest", "job_parent", "trc_1", preview,
                        PolicyDecision.allow("mock"), new ChangeTicket("CHG0012345", "https://servicenow.example/CHG0012345", "new"), T0)
                .toBuilder()
                .status(JobStatus.NEEDS_ATTENTION)
                .steps(List.of(done, failed))
                .approvals(List.of(approval))
                .attention(new Attention("noBackupRunning", failed.error(), Attention.OPTIONS, T0, "24h"))
                .resume(new Job.Resume("noBackupRunning", List.of(done)))
                .row(3)
                .preApproved(true)
                .build();
    }

    @Test
    void aJobRoundTripsWithItsDefinitionInputAndRecords() {
        Job job = richJob();

        Job back = roundTrip(job);

        assertThat(back).isEqualTo(job);
        assertThat(back.definition()).isEqualTo(job.definition());
        assertThat(back.input().get("reason").isNull()).isTrue();
        assertThat(back.input().get("big").isLong()).isTrue();
        assertThat(back.input().get("ratio").isDouble()).isTrue();
        assertThat(back.input().path("nested").path("tags").get(1).isNull()).isTrue();
        assertThat(back.startedAt()).isEqualTo(T0);
        assertThat(back.requester().kind()).isEqualTo(Identity.Kind.USER);
        assertThat(back.isBatch()).isFalse();
        String json = json(job);
        assertThat(json).contains("\"kind\":\"single\"", "\"status\":\"needs-attention\"", "\"startedAt\":\"2026-10-17T10:00:00.123456789Z\"",
                "\"reason\":null");
        // nulls are dropped from records (they come back null), never from the JsonNodes the job carries;
        // and a single job has no "batch" at all (isBatch() is a helper, not a property)
        JsonNode tree = read(json);
        assertThat(tree.has("endedAt")).isFalse();
        assertThat(tree.has("batch")).isFalse();
        assertThat(tree.has("preApproved")).isTrue();
    }

    private static JsonNode read(String json) {
        try {
            return TestWeb.json().readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void everySampleDefinitionRoundTrips() {
        assertThat(SampleContent.workflows()).isNotEmpty();
        for (WorkflowDefinition def : SampleContent.workflows()) {
            assertThat(roundTrip(def)).as(def.name()).isEqualTo(def);
        }
    }

    @Test
    void aBatchKeepsItsRowsAndWaves() {
        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rows.add(SampleContent.json(Map.of("vmId", "vm-" + i)));
        }
        RolloutPolicy rollout = new RolloutPolicy(List.of(50, 100), 2, 5);
        Job batch = JobFactory.newBatch(def, def.version(), rows, SampleContent.json(Map.of("reason", "patch")), rollout, WaveScheduler.plan(4, rollout),
                SampleContent.ANA, "batch", null, null, null, T0);

        Job back = roundTrip(batch);

        assertThat(back).isEqualTo(batch);
        assertThat(back.isBatch()).isTrue();
        assertThat(back.batch().rows()).hasSize(4);
        assertThat(back.batch().waves()).extracting(Batch.Wave::size).containsExactly(2, 2);
        assertThat(back.batch().rollout()).isEqualTo(rollout);
        assertThat(json(batch)).contains("\"kind\":\"batch\"", "\"batch\":{\"rows\":[");
    }

    @Test
    void signalsResultsAndActivityArgumentsRoundTrip() {
        Step step = SampleContent.workflow("vm-restart").steps().get(0);
        JobRef ref = JobRef.of(richJob());
        List<Object> values = List.of(
                ApprovalDecision.approved("gonzalo", "go", T0),
                AttentionDecision.editInput(SampleContent.json(Map.of("id", "vm-2")), "ana"),
                AttentionDecision.abort("ana"),
                Outcome.succeeded(SampleContent.json(Map.of("vmName", "web-1042"))),
                new Outcome(JobStatus.ROLLED_BACK, null, "timeout", "waitReady", "guest tools never came up"),
                StepFailureData.of(new StepFailure("restart", "unreachable", "host down", true)),
                ref,
                new StepCall(ref, step, 7, Duration.ofMinutes(30)),
                new StepCall(ref, step, 0, null),
                new StepEventData("restart", "Restart", StepType.ANSIBLE, StepStatus.FAILED, 2, input(), null,
                        new StepError("timeout", "too slow", true), T0, 1500L, null, false),
                new UseRequest("vm", "vm-1042", "ready", true),
                new RegisterRequest("vm", null, "ready", input(), List.of("img-77")),
                new Attention("restart", new StepError("timeout", "too slow", true), Attention.OPTIONS, T0, "24h"),
                new Batch.Wave(1, 50, 2, "running", T0, null),
                new Batch.BatchRow(2, input(), "job_2", "failed", "assertion_failed", 1));
        for (Object value : values) {
            assertThat(roundTrip(value)).as(value.getClass().getSimpleName()).isEqualTo(value);
        }
        assertThat(json(AttentionDecision.editInput(F.objectNode(), "ana"))).contains("\"action\":\"edit-input\"");
    }

    @Test
    void theJsonNodesOfAStepRoundTripAsTheyAre() {
        JsonNode with = SampleContent.workflow("vm-restart").steps().get(3).with();

        assertThat(roundTrip(with)).isEqualTo(with);
        assertThat(roundTrip((JsonNode) input())).isEqualTo(input());
    }
}
