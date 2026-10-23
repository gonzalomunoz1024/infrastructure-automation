package com.iaf.console.drift.drift.adapters.temporal;

import java.time.Duration;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Workflow;

/**
 * The loop: each turn is one activity (a shard scan, a sync or a follower pass), retried a few
 * times; a turn that still fails is skipped, since the next one reads what is due again. The
 * workflow continues as new after its iterations, so its history stays a few hundred events.
 */
public class DriftLoopWorkflowImpl implements DriftLoopWorkflow {

    private final LoopActivities activities = Workflow.newActivityStub(LoopActivities.class, ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofMinutes(15))
            .setRetryOptions(RetryOptions.newBuilder()
                    .setInitialInterval(Duration.ofSeconds(5))
                    .setMaximumInterval(Duration.ofMinutes(1))
                    .setMaximumAttempts(3)
                    .build())
            .build());

    @Override
    public void run(LoopSpec spec) {
        for (int turn = 0; turn < spec.iterations(); turn++) {
            try {
                activities.tick(spec);
            } catch (ActivityFailure e) {
                Workflow.getLogger(DriftLoopWorkflowImpl.class).warn("{} turn {} failed: {}", spec.workflowId(), turn, e.getMessage());
            }
            Workflow.sleep(Duration.ofMillis(spec.intervalMillis()));
        }
        Workflow.continueAsNew(spec);
    }
}
