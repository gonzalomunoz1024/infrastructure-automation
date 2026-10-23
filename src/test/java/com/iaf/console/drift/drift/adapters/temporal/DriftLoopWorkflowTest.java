package com.iaf.console.drift.drift.adapters.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.console.drift.drift.domain.TierRules;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

/** The shard loops on Temporal's test server, with time skipped: turns, a failing turn, continue-as-new, one loop per id. */
class DriftLoopWorkflowTest {

    private static final String QUEUE = "console-drift-global";

    /** Counts the turns; the turns of the {@code broken} loop always fail. */
    static final class CountingActivities implements LoopActivities {
        final AtomicInteger ticks = new AtomicInteger();
        final AtomicInteger brokenAttempts = new AtomicInteger();

        @Override
        public String tick(LoopSpec spec) {
            if ("broken".equals(spec.tier())) {
                brokenAttempts.incrementAndGet();
                throw new IllegalStateException("the platform is down");
            }
            return "turn " + ticks.incrementAndGet();
        }
    }

    private TestWorkflowEnvironment env;
    private CountingActivities activities;
    private WorkflowClient client;

    @BeforeEach
    void start() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(QUEUE);
        worker.registerWorkflowImplementationTypes(DriftLoopWorkflowImpl.class);
        activities = new CountingActivities();
        worker.registerActivitiesImplementations(activities);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void stop() {
        env.close();
    }

    @Test
    void aLoopTurnsSleepsAndContinuesAsNewUnderTheSameId() {
        LoopSpec spec = new LoopSpec(LoopSpec.SCAN, "global", 3, "hot", Duration.ofMinutes(1).toMillis(), 3);
        assertThat(TemporalLoops.startLoop(client, QUEUE, spec)).isTrue();
        // the second start of the same loop finds the first one running
        assertThat(TemporalLoops.startLoop(client, QUEUE, spec)).isFalse();

        env.sleep(Duration.ofMinutes(10));
        // ten minutes of one-minute turns, across three continue-as-new runs
        assertThat(activities.ticks.get()).isBetween(8, 11);
        var description = env.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(env.getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(spec.workflowId()).build())
                .build());
        assertThat(description.getWorkflowExecutionInfo().getStatus()).isEqualTo(WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING);
        assertThat(spec.workflowId()).isEqualTo("console-drift-global-scan-3-hot");
    }

    @Test
    void aFailingTurnIsRetriedThenSkippedAndTheLoopGoesOn() {
        LoopSpec spec = new LoopSpec(LoopSpec.SCAN, "global", 0, "broken", Duration.ofMinutes(1).toMillis(), 100);
        TemporalLoops.startLoop(client, QUEUE, spec);
        env.sleep(Duration.ofMinutes(5));
        // three attempts a turn, several turns: the loop did not stop at the first failure
        assertThat(activities.brokenAttempts.get()).isGreaterThanOrEqualTo(6);
    }

    @Test
    void theLoopsOfARegionWakeWithinTheirTiers() {
        TierRules tiers = TierRules.defaults();
        List<LoopSpec> specs = TemporalLoops.specs("global", 4, tiers, Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(5), 96);
        assertThat(specs).hasSize(3 * 4 + 2);
        assertThat(specs).extracting(LoopSpec::workflowId).doesNotHaveDuplicates();
        assertThat(TemporalLoops.wakeOf(Duration.ofMinutes(15), Duration.ofSeconds(30))).isEqualTo(Duration.ofMinutes(1));
        assertThat(TemporalLoops.wakeOf(Duration.ofHours(24), Duration.ofSeconds(30))).isEqualTo(Duration.ofMinutes(5));
        assertThat(TemporalLoops.wakeOf(Duration.ofSeconds(10), Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
    }
}
