package com.iaf.platform.runs.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.Attention;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobContext;
import com.iaf.platform.workflows.domain.Step;

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
}
