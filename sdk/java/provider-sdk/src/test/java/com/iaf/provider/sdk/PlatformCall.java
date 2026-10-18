package com.iaf.provider.sdk;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.CanceledFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.workflow.ActivityStub;
import io.temporal.workflow.CancellationScope;
import io.temporal.workflow.Promise;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Calls an action the way the platform's interpreter does: an activity of the action's type on
 * the provider's task queue, no Temporal-level retries, cancelled when the step is. Answers what
 * came back: {@code {output}}, {@code {type, nonRetryable, message}}, {@code {timeout}} or
 * {@code {cancelled}}.
 */
@WorkflowInterface
public interface PlatformCall {

    String QUEUE = "platform";

    @WorkflowMethod
    JsonNode call(String taskQueue, String action, JsonNode payload, long heartbeatTimeoutMillis);

    @SignalMethod
    void cancel();

    class Impl implements PlatformCall {

        private boolean cancelled;
        private Promise<JsonNode> pending;

        @Override
        public JsonNode call(String taskQueue, String action, JsonNode payload, long heartbeatTimeoutMillis) {
            ActivityOptions.Builder options = ActivityOptions.newBuilder()
                    .setTaskQueue(taskQueue)
                    .setStartToCloseTimeout(Duration.ofSeconds(20))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED);
            if (heartbeatTimeoutMillis > 0) {
                options.setHeartbeatTimeout(Duration.ofMillis(heartbeatTimeoutMillis));
            }
            ActivityStub stub = Workflow.newUntypedActivityStub(options.build());
            CancellationScope scope = Workflow.newCancellationScope(() -> pending = stub.executeAsync(action, JsonNode.class, payload));
            scope.run();
            Workflow.await(() -> pending.isCompleted() || cancelled);
            if (!pending.isCompleted()) {
                scope.cancel("the step was cancelled");
            }
            ObjectNode outcome = JsonNodeFactory.instance.objectNode();
            try {
                outcome.set("output", pending.get());
            } catch (ActivityFailure f) {
                if (f.getCause() instanceof ApplicationFailure a) {
                    outcome.put("type", a.getType()).put("nonRetryable", a.isNonRetryable()).put("message", a.getOriginalMessage());
                } else if (f.getCause() instanceof TimeoutFailure t) {
                    outcome.put("timeout", t.getTimeoutType().name());
                } else if (f.getCause() instanceof CanceledFailure) {
                    outcome.put("cancelled", true);
                } else {
                    outcome.put("failure", String.valueOf(f.getCause()));
                }
            }
            return outcome;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
