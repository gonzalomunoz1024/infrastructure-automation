package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.PortStepExecutor.Ports;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.runs.domain.StepFailure;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import reactor.core.publisher.Mono;

/** The engine seam of the port executor. */
class PortStepExecutorTest {

    /** An engine with no registry events: it only knows how to sleep. */
    static final class SleepingEngine implements PortStepExecutor.Engine {
        final List<Duration> slept = new ArrayList<>();

        @Override
        public Optional<ApprovalDecision> awaitDecision(JobContext ctx, String approvalId, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<AttentionDecision> awaitAttention(JobContext ctx, Attention attention, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void sleep(JobContext ctx, Duration duration) {
            slept.add(duration);
        }

        @Override
        public boolean cancelled(JobContext ctx) {
            return false;
        }

        @Override
        public JsonNode runChild(JobContext ctx, Step step, Job child, Duration timeout) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void anEngineWithoutRegistryEventsLooksAgainEveryPollIntervalWithinTheTimeout() {
        SleepingEngine engine = new SleepingEngine();

        assertThat(engine.awaitResource(null, "image", "img-1", "ready", Duration.ofMinutes(10))).as("look again").isTrue();
        assertThat(engine.awaitResource(null, "image", "img-1", "ready", Duration.ofSeconds(2))).isTrue();

        assertThat(engine.slept).containsExactly(PortStepExecutor.USE_POLL, Duration.ofSeconds(2));
    }

    private static Job job(RunsTestWiring w) {
        WorkflowDefinition d = SampleContent.workflow("vm-restart");
        return JobFactory.newJob(d, d.version(), SampleContent.json(Map.of("vmId", "vm-1042")), SampleContent.GONZALO, "rest", null, null, null, null, null,
                w.clock.instant());
    }

    @Test
    void aProviderCallThatOutlivesTheStepTimeoutFailsTheStepAsATimeout() {
        RunsTestWiring w = new RunsTestWiring();
        Ports p = w.executors.ports();
        Ports silent = new Ports(p.providers(), call -> Mono.never(), p.runners(), p.workflows(), p.resources(), p.resourceCommands(), p.events(),
                p.policy(), p.clock());
        Job job = job(w);
        PortStepExecutor executor = new PortStepExecutor(silent, new SleepingEngine(), job);
        Step get = Step.simple("get", "Get", StepType.PROVIDER, SampleContent.json(Map.of()));

        assertThatThrownBy(() -> executor.callProvider(JobContext.of(job), get, "vsphere", "vm.get", SampleContent.json(Map.of("id", "vm-1042")),
                Duration.ofMillis(50)))
                .isInstanceOfSatisfying(StepFailure.class, f -> {
                    assertThat(f.errorClass()).isEqualTo("timeout");
                    assertThat(f.retryable()).isTrue();
                    assertThat(f.getMessage()).isEqualTo("Step get timed out");
                });
    }

    @Test
    void aRunnerThatDoesNotAnswerWithinTheLimitIsTheStepsTimeout() {
        RunsTestWiring w = new RunsTestWiring();
        Job job = job(w);
        PortStepExecutor executor = new PortStepExecutor(w.executors.ports(), new SleepingEngine(), job);

        assertThatThrownBy(() -> executor.await(Mono.never(), Duration.ofMillis(50), JobContext.of(job)))
                .isInstanceOfSatisfying(RuntimeException.class, e -> {
                    StepFailure f = PortStepExecutor.translate("run", e, false);
                    assertThat(f.errorClass()).isEqualTo("timeout");
                    assertThat(f.retryable()).isTrue();
                });
    }
}
