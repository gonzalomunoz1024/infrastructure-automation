package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import reactor.test.StepVerifier;

class WorkerActivityRunnerTest {

    final WorkerActivityRunner runner = new WorkerActivityRunner();
    final ObjectNode with = JsonNodeFactory.instance.objectNode().put("taskQueue", "team-x-activities").put("activity", "crunchNumbers");

    @Test
    void takesOnlyActivitySteps() {
        assertThat(runner.supports(StepType.ACTIVITY)).isTrue();
        assertThat(runner.supports(StepType.ANSIBLE)).isFalse();
        assertThat(runner.supports(StepType.SCRIPT)).isFalse();
    }

    @Test
    void anActivityStepOutsideTemporalFailsSayingItNeedsTheTeamsWorker() {
        StepVerifier.create(runner.run(new RunRequest("job_1", 1, StepType.ACTIVITY, null, with, "dev", false, false)))
                .expectErrorSatisfies(e -> {
                    ProviderCallException failure = (ProviderCallException) e;
                    assertThat(failure.errorClass()).isEqualTo("unsupported");
                    assertThat(failure.retryable()).isFalse();
                    assertThat(failure.getMessage()).contains("crunchNumbers", "team-x-activities", "Temporal worker", "temporal profile");
                })
                .verify();
    }

    @Test
    void aPreviewStillReadsLikeTheRecordedAnswer() {
        StepVerifier.create(runner.run(new RunRequest("job_1", 1, StepType.ACTIVITY, null, with, "dev", true, false)))
                .assertNext(out -> {
                    assertThat(out.path("ok").asBoolean()).isTrue();
                    assertThat(out.path("activity").asText()).isEqualTo("crunchNumbers");
                })
                .verifyComplete();
    }
}
