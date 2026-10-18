package com.iaf.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.domain.InputProblemsException;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A call pinned to a version: judged against that version, run on it, and never split by a canary. */
public class WorkflowInvokerPinningTest {

    static final JsonNodeFactory F = JsonNodeFactory.instance;

    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    WorkflowQueries workflows = mock(WorkflowQueries.class);
    WorkflowInvoker invoker = new WorkflowInvoker(runs, policy, JobQuotas.unlimited(), workflows);
    WorkflowRecord published = SampleCatalog.workflow("vm-restart");

    /** The sample vm-restart at another version; the older one also wanted a reason. */
    public static WorkflowRecord restart(String version, WorkflowStatus status, boolean reasonRequired) {
        String yaml;
        try {
            yaml = Files.readString(SampleCatalog.ROOT.resolve("workflows/team-compute/vm-restart.yaml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        yaml = yaml.replace("  version: 1.2.0", "  version: " + version);
        if (reasonRequired) {
            yaml = yaml.replace("required: [vmId]", "required: [vmId, reason]");
        }
        return WorkflowRecord.of(WorkflowYaml.parse(yaml), status, "workflows/team-compute/vm-restart.yaml", Instant.EPOCH, "seed");
    }

    static ObjectNode input(String vmId, String reason) {
        ObjectNode in = F.objectNode().put("vmId", vmId);
        if (reason != null) {
            in.put("reason", reason);
        }
        return in;
    }

    @BeforeEach
    void ports() {
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenAnswer(inv -> Mono.just(new Preview(((WorkflowDefinition) inv.getArgument(0)).name(),
                ((WorkflowDefinition) inv.getArgument(0)).version(), List.of(new Preview.PreviewStep("restart", "Restart", Effect.CHANGE, "power cycle")),
                List.of(), null, PolicyDecision.allow("mock"))));
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
        when(workflows.version("vm-restart", "1.1.0")).thenReturn(Mono.just(restart("1.1.0", WorkflowStatus.RETIRED, true)));
        when(workflows.version("vm-restart", "1.3.0")).thenReturn(Mono.just(restart("1.3.0", WorkflowStatus.DRAFT, false)));
        when(workflows.version("vm-restart", "9.9.9")).thenReturn(Mono.empty());
    }

    StartJob started() {
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        return captor.getValue();
    }

    PolicyInput judged() {
        ArgumentCaptor<PolicyInput> captor = ArgumentCaptor.forClass(PolicyInput.class);
        verify(policy).evaluate(captor.capture());
        return captor.getValue();
    }

    @Test
    void anUnpinnedCallLeavesTheVersionToTheReleaseRouter() {
        StepVerifier.create(invoker.invoke(published, input("vm-1", null), SampleCatalog.OPS, "rest", "t1")).expectNextCount(1).verifyComplete();
        assertThat(started().version()).isNull();
        assertThat(judged().context().has("pinnedVersion")).isFalse();
        verifyNoInteractions(workflows);
    }

    @Test
    void pinningThePublishedVersionSkipsTheCanarySplitWithoutALookup() {
        StepVerifier.create(invoker.invoke(published, input("vm-1", null), SampleCatalog.OPS, "rest", "t1", "1.2.0")).expectNextCount(1).verifyComplete();
        assertThat(started().version()).isEqualTo("1.2.0");
        PolicyInput judged = judged();
        assertThat(judged.version()).isEqualTo("1.2.0");
        assertThat(judged.context().path("pinnedVersion").asText()).isEqualTo("1.2.0");
        verify(workflows, never()).version(any(), any());
    }

    @Test
    void aPinnedRetiredVersionIsJudgedByItsOwnDefinitionAndRunsAsIs() {
        StepVerifier.create(invoker.invoke(published, input("vm-1", null), SampleCatalog.OPS, "rest", "t1", "1.1.0"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(InputProblemsException.class).hasMessageContaining("vm-restart"))
                .verify();
        verify(runs, never()).startJob(any());

        StepVerifier.create(invoker.invoke(published, input("vm-1", "stuck"), SampleCatalog.OPS, "rest", "t1", "1.1.0")).expectNextCount(1).verifyComplete();
        assertThat(started().version()).isEqualTo("1.1.0");
        assertThat(judged().version()).isEqualTo("1.1.0");
    }

    @Test
    void aDraftOrAnUnknownVersionCannotBePinned() {
        StepVerifier.create(invoker.invoke(published, input("vm-1", null), SampleCatalog.OPS, "rest", "t1", "1.3.0"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(ConflictException.class)
                        .hasMessage("vm-restart@1.3.0 is draft; only published or retired versions can be pinned"))
                .verify();
        StepVerifier.create(invoker.invoke(published, input("vm-1", null), SampleCatalog.OPS, "rest", "t1", "9.9.9"))
                .expectErrorSatisfies(e -> assertThat(e).isInstanceOf(NotFoundException.class).hasMessage("workflow version vm-restart@9.9.9 not found"))
                .verify();
        verify(runs, never()).startJob(any());
        verify(policy, never()).evaluate(any());
    }

    @Test
    void aPinnedBatchRunsEveryRowOnThatVersion() {
        ObjectNode body = F.objectNode();
        body.putArray("items").add(input("vm-1", "a")).add(input("vm-2", "b"));
        StepVerifier.create(invoker.batch(published, body, Map.of(), SampleCatalog.OPS, "rest", "1.1.0"))
                .assertNext(b -> assertThat(b.rows()).isEqualTo(2))
                .verifyComplete();
        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        assertThat(captor.getValue().version()).isEqualTo("1.1.0");

        ObjectNode noReason = F.objectNode();
        noReason.putArray("items").add(input("vm-1", null));
        StepVerifier.create(invoker.batch(published, noReason, Map.of(), SampleCatalog.OPS, "rest", "1.1.0")).expectError().verify();
        StepVerifier.create(invoker.batch(published, noReason, Map.of(), SampleCatalog.OPS, "rest")).expectNextCount(1).verifyComplete();
    }

    @Test
    void aPinnedPreviewShowsThatVersion() {
        StepVerifier.create(invoker.preview(published, input("vm-1", "stuck"), SampleCatalog.OPS, "1.1.0"))
                .assertNext(p -> assertThat(p.version()).isEqualTo("1.1.0"))
                .verifyComplete();
        ArgumentCaptor<WorkflowDefinition> captor = ArgumentCaptor.forClass(WorkflowDefinition.class);
        verify(runs).preview(captor.capture(), any(JsonNode.class), eq(SampleCatalog.OPS));
        assertThat(captor.getValue().version()).isEqualTo("1.1.0");
    }
}
