package com.iaf.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.domain.BatchProblemsException;
import com.iaf.platform.catalog.domain.InputProblemsException;
import com.iaf.platform.catalog.domain.PolicyDeniedException;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.gateway.application.GatewayProperties;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.workflows.domain.WorkflowDefinition.RolloutPolicy;
import com.iaf.platform.workflows.domain.WorkflowRecord;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowInvokerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    WorkflowInvoker invoker = new WorkflowInvoker(runs, policy);
    WorkflowRecord restart = SampleCatalog.workflow("vm-restart");
    WorkflowRecord create = SampleCatalog.workflow("create-vm");

    static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    void allow() {
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.just(new Preview("vm-restart", "1.2.0", List.of(), List.of(), null, PolicyDecision.allow("mock"))));
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    @Test
    void validatesJudgesRbacAsksPolicyPreviewsThenStarts() {
        allow();
        StepVerifier.create(invoker.invoke(restart, json("{\"vmId\":\"vm-1\",\"reason\":\"patching\"}"), SampleCatalog.OPS, "rest", "trace-1"))
                .assertNext(started -> {
                    assertThat(started.jobId()).isEqualTo("job_1");
                    assertThat(started.status()).isEqualTo("queued");
                    assertThat(started.links()).containsEntry("job", "/api/v1/jobs/job_1");
                })
                .verifyComplete();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        StartJob job = captor.getValue();
        assertThat(job.workflow()).isEqualTo("vm-restart");
        assertThat(job.version()).isNull();
        assertThat(job.input().path("vmId").asText()).isEqualTo("vm-1");
        assertThat(job.requester()).isEqualTo(SampleCatalog.OPS);
        assertThat(job.channel()).isEqualTo("rest");
        assertThat(job.parentId()).isNull();
        assertThat(job.traceId()).isEqualTo("trace-1");
        assertThat(job.preview()).isNotNull();
        assertThat(job.changeId()).isNull();
        ArgumentCaptor<PolicyPort.PolicyInput> policyInput = ArgumentCaptor.forClass(PolicyPort.PolicyInput.class);
        verify(policy).evaluate(policyInput.capture());
        assertThat(policyInput.getValue().phase()).isEqualTo(PolicyPort.Phase.INVOKE);
        assertThat(policyInput.getValue().workflow()).isEqualTo("vm-restart");
        assertThat(policyInput.getValue().version()).isEqualTo("1.2.0");
    }

    @Test
    void noPreviewWhenTheWorkflowDoesNotPublishOne() {
        allow();
        WorkflowRecord build = SampleCatalog.workflow("build-vm-image");
        StepVerifier.create(invoker.invoke(build, json("{\"os\":\"rhel-9\"}"), SampleCatalog.ADMIN, "mcp", "t")).expectNextCount(1).verifyComplete();
        verify(runs, never()).preview(any(), any(), any());
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        assertThat(captor.getValue().preview()).isNull();
    }

    @Test
    void schemaProblemsStopEverything() {
        allow();
        StepVerifier.create(invoker.invoke(restart, json("{\"reason\":\"no id\"}"), SampleCatalog.OPS, "rest", "t"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(InputProblemsException.class);
                    assertThat(((InputProblemsException) e).problems()).anyMatch(p -> p.message().contains("vmId"));
                })
                .verify();
        verify(policy, never()).evaluate(any());
        verify(runs, never()).startJob(any());
    }

    @Test
    void rbacBeforePolicy() {
        allow();
        StepVerifier.create(invoker.invoke(restart, json("{\"vmId\":\"vm-1\"}"), SampleCatalog.GUEST, "rest", "t"))
                .expectError(ForbiddenException.class)
                .verify();
        verify(policy, never()).evaluate(any());
        verify(runs, never()).startJob(any());
    }

    @Test
    void aPolicyDenialCarriesTheRules() {
        allow();
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("prod-freeze", "deny", "Production is frozen")), "mock")));
        StepVerifier.create(invoker.invoke(restart, json("{\"vmId\":\"vm-1\"}"), SampleCatalog.OPS, "rest", "t"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(PolicyDeniedException.class);
                    assertThat(((PolicyDeniedException) e).rules()).extracting(Rule::id).containsExactly("prod-freeze");
                    assertThat(e.getMessage()).contains("Production is frozen");
                })
                .verify();
        verify(runs, never()).startJob(any());
    }

    @Test
    void needsApprovalStillStartsTheJob() {
        allow();
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("needs-approval", List.of(), "mock")));
        StepVerifier.create(invoker.invoke(restart, json("{\"vmId\":\"vm-1\"}"), SampleCatalog.OPS, "rest", "t")).expectNextCount(1).verifyComplete();
    }

    @Test
    void aBatchValidatesEveryRowWithTheDefaultsUnderneathBeforeAnythingStarts() {
        allow();
        JsonNode body = json("""
                { "items": [ { "name": "web-1" }, { "name": "BAD NAME" }, { "name": "web-3", "size": "huge" } ],
                  "defaults": { "image": "image-demo01", "size": "small" } }
                """);
        StepVerifier.create(invoker.batch(create, body, Map.of(), SampleCatalog.OPS, "rest"))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(BatchProblemsException.class);
                    var rows = ((BatchProblemsException) e).rows();
                    assertThat(rows).extracting(r -> r.index()).containsExactly(1, 2);
                    assertThat(rows.get(0).problems()).isNotEmpty();
                })
                .verify();
        verify(runs, never()).startBatch(any());
        verify(policy, never()).evaluate(any());
    }

    @Test
    void aValidBatchStartsOnceWithOnePolicyCallAndOnePreview() {
        allow();
        JsonNode body = json("""
                { "items": [ { "name": "web-1" }, { "name": "web-2", "size": "medium" } ],
                  "defaults": { "image": "image-demo01", "size": "small" },
                  "rollout": { "waves": [10, 100], "maxParallel": 3 } }
                """);
        StepVerifier.create(invoker.batch(create, body, Map.of(), SampleCatalog.OPS, "rest"))
                .assertNext(b -> {
                    assertThat(b.jobId()).isEqualTo("job_b");
                    assertThat(b.rows()).isEqualTo(2);
                    assertThat(b.links()).containsEntry("job", "/api/v1/jobs/job_b");
                })
                .verifyComplete();
        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        StartBatch batch = captor.getValue();
        assertThat(batch.workflow()).isEqualTo("create-vm");
        assertThat(batch.items()).hasSize(2);
        assertThat(batch.items().get(0).path("size").asText()).isEqualTo("small");
        assertThat(batch.items().get(1).path("size").asText()).isEqualTo("medium");
        assertThat(batch.items().get(1).path("image").asText()).isEqualTo("image-demo01");
        assertThat(batch.rollout().waves()).containsExactly(10, 100);
        assertThat(batch.rollout().maxParallel()).isEqualTo(3);
        assertThat(batch.rollout().stopIfFailedPercent()).isEqualTo(10);
        assertThat(batch.preview()).isNotNull();
        assertThat(batch.channel()).isEqualTo("rest");
        verify(policy).evaluate(any());
        verify(runs).preview(any(), any(), any());
    }

    @Test
    void aBatchRespectsTheWorkflowsMaxAndNeedsItems() {
        allow();
        assertThatThrownBy(() -> invoker.plan(create.definition(), json("{\"items\":[]}"), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoker.plan(create.definition(), json("{}"), Map.of())).isInstanceOf(IllegalArgumentException.class);
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i > 0 ? "," : "").append("{\"name\":\"w\",\"image\":\"i\",\"size\":\"small\"}");
        }
        many.append("]}");
        assertThatThrownBy(() -> invoker.plan(create.definition(), json(many.toString()), Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at most 200");
    }

    @Test
    void rolloutFallsBackToTheWorkflowDefaults() {
        RolloutPolicy dflt = new RolloutPolicy(List.of(1, 5, 25), 2, 1);
        assertThat(WorkflowInvoker.rollout(null, dflt)).isEqualTo(dflt);
        assertThat(WorkflowInvoker.rollout(json("{\"maxParallel\": 4}"), dflt)).isEqualTo(new RolloutPolicy(List.of(1, 5, 25), 4, 1));
        assertThatThrownBy(() -> WorkflowInvoker.rollout(json("{\"waves\": [50, 10]}"), dflt)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WorkflowInvoker.rollout(json("{\"stopIfFailedPercent\": 101}"), dflt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aDefaultedFieldMayBeLeftOutEvenWhenTheSchemaRequiresIt() {
        allow();
        // create-vm requires size, which defaults to small: the caller may leave it out, and the job and the policy see the default
        StepVerifier.create(invoker.invoke(create, json("{\"name\":\"web-09\",\"image\":\"img-1\"}"), SampleCatalog.OPS, "rest", null))
                .assertNext(started -> assertThat(started.jobId()).isEqualTo("job_1")).verifyComplete();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        assertThat(captor.getValue().input().path("size").asText()).isEqualTo("small");
        assertThat(captor.getValue().input().path("environment").asText()).isEqualTo("dev");
        ArgumentCaptor<PolicyPort.PolicyInput> policyInput = ArgumentCaptor.forClass(PolicyPort.PolicyInput.class);
        verify(policy).evaluate(policyInput.capture());
        assertThat(policyInput.getValue().input().path("size").asText()).isEqualTo("small");
        // a preview fills them in the same way
        ArgumentCaptor<JsonNode> previewed = ArgumentCaptor.forClass(JsonNode.class);
        StepVerifier.create(invoker.preview(create, json("{\"name\":\"web-09\",\"image\":\"img-1\"}"), SampleCatalog.OPS)).expectNextCount(1).verifyComplete();
        verify(runs, org.mockito.Mockito.atLeastOnce()).preview(any(), previewed.capture(), any());
        assertThat(previewed.getValue().path("size").asText()).isEqualTo("small");
        // and a batch row too, under the body's own defaults
        WorkflowInvoker.BatchPlan plan = invoker.plan(create.definition(), json("{\"items\":[{\"name\":\"web-10\",\"image\":\"img-1\"},{\"name\":\"web-11\",\"image\":\"img-1\",\"size\":\"large\"}]}"), Map.of());
        assertThat(plan.rows().get(0).path("size").asText()).isEqualTo("small");
        assertThat(plan.rows().get(1).path("size").asText()).isEqualTo("large");
        // a field without a default is still required
        StepVerifier.create(invoker.invoke(create, json("{\"image\":\"img-1\"}"), SampleCatalog.OPS, "rest", null)).expectError(InputProblemsException.class).verify();
    }

    @Test
    void previewValidatesAndJudgesRbacButNeverStarts() {
        allow();
        StepVerifier.create(invoker.preview(restart, json("{\"vmId\":\"vm-1\"}"), SampleCatalog.OPS))
                .assertNext(p -> assertThat(p.workflow()).isEqualTo("vm-restart")).verifyComplete();
        StepVerifier.create(invoker.preview(restart, json("{}"), SampleCatalog.OPS)).expectError(InputProblemsException.class).verify();
        StepVerifier.create(invoker.preview(restart, json("{\"vmId\":\"vm-1\"}"), SampleCatalog.GUEST)).expectError(ForbiddenException.class).verify();
        verify(runs, never()).startJob(any());
    }


    @Test
    void aRejectedRequestLeavesTheBucketUntouched() {
        allow();
        GatewayProperties.Quotas one = new GatewayProperties.Quotas();
        one.setBurst(1);
        WorkflowInvoker limited = new WorkflowInvoker(runs, policy, new JobQuotas(one, java.time.Clock.systemUTC()));
        JsonNode valid = json("{\"vmId\":\"vm-1\"}");

        StepVerifier.create(limited.invoke(restart, json("{\"reason\":\"no id\"}"), SampleCatalog.OPS, "rest", "t")).expectError(InputProblemsException.class).verify();
        StepVerifier.create(limited.invoke(restart, valid, SampleCatalog.GUEST, "rest", "t")).expectError(ForbiddenException.class).verify();
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("freeze", "deny", "Frozen")), "mock")));
        StepVerifier.create(limited.invoke(restart, valid, SampleCatalog.OPS, "rest", "t")).expectError(PolicyDeniedException.class).verify();
        StepVerifier.create(limited.batch(create, json("{\"items\":[{\"name\":\"BAD NAME\"}]}"), Map.of(), SampleCatalog.OPS, "rest"))
                .expectError(BatchProblemsException.class).verify();
        allow();

        StepVerifier.create(limited.invoke(restart, valid, SampleCatalog.OPS, "rest", "t")).expectNextCount(1).verifyComplete();
        StepVerifier.create(limited.invoke(restart, valid, SampleCatalog.OPS, "rest", "t")).expectError(TooManyRequestsException.class).verify();
        verify(runs, times(1)).startJob(any());
    }
}
