package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.runs.application.PortStepExecutor;
import com.iaf.platform.runs.domain.Durations;
import com.iaf.platform.runs.domain.StepFailure;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;

/**
 * The contract between the interpreter and a team's worker, which the provider SDKs implement
 * on the other side (docs/architecture.md, "Custom-code providers"). The activity type is the
 * action id (or the activity a {@code call: activity} names) and its one argument is a JSON object:
 *
 * <pre>
 * { "input": {...}, "idempotencyKey": "job-…:reserve:3",
 *   "job": { "id", "workflow", "version", "traceId" }, "step": "reserve", "environment": "prod", "attempt": 1 }
 * </pre>
 *
 * <p>The result is the action's output, a JSON object. The activity runs once: the step's timeout
 * is its start-to-close and its schedule-to-close (a call no worker picks up fails instead of
 * waiting in the queue for good), its heartbeat timeout is the provider's, and the interpreter
 * does the retrying. A failure comes back classified: an {@code ApplicationFailure}'s type is the
 * error class and non-retryable means the interpreter must not retry it, a timeout is
 * {@value #TIMEOUT} and a heartbeat timeout {@value #WORKER_LOST}.</p>
 *
 * <p>Pure functions of their arguments: they run inside the workflow.</p>
 */
final class WorkerCalls {

    static final String TIMEOUT = "timeout";
    static final String WORKER_LOST = "worker-lost";
    private static final RetryOptions ONCE = RetryOptions.newBuilder().setMaximumAttempts(1).build();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private WorkerCalls() {
    }

    /**
     * The key a worker deduplicates a call's side effect by: the job, the step and the
     * {@code $job.seq} value of the step's first attempt, so the step's retries send the same key
     * and a worker restart replays it unchanged. A new run of the step (a loop's next item, a
     * person's retry or edited input) gets a new one.
     */
    static String idempotencyKey(String jobId, String stepId, int seq) {
        return jobId + ":" + stepId + ":" + seq;
    }

    static ObjectNode payload(JobRef job, String stepId, String idempotencyKey, int attempt, JsonNode input) {
        ObjectNode p = F.objectNode();
        p.set("input", input == null || input.isMissingNode() || input.isNull() ? F.objectNode() : input);
        p.put("idempotencyKey", idempotencyKey);
        ObjectNode j = p.putObject("job");
        j.put("id", job.id());
        j.put("workflow", job.workflow());
        j.put("version", job.version());
        j.put("traceId", job.traceId());
        p.put("step", stepId);
        p.put("environment", job.environment());
        p.put("attempt", attempt);
        return p;
    }

    /**
     * One attempt on the worker's queue, as long as the step may take from the moment it is asked
     * (schedule-to-close, so a queue nobody polls fails the step) and once a worker has it
     * (start-to-close); the heartbeat timeout only when the provider sets one.
     */
    static ActivityOptions options(WorkerRoute route, Duration stepTimeout) {
        Duration limit = stepTimeout == null ? PortStepExecutor.DEFAULT_CALL_TIMEOUT : stepTimeout;
        ActivityOptions.Builder b = ActivityOptions.newBuilder()
                .setTaskQueue(route.taskQueue())
                .setScheduleToCloseTimeout(limit)
                .setStartToCloseTimeout(limit)
                .setRetryOptions(ONCE);
        if (route.heartbeatTimeout() != null && route.heartbeatTimeout().isPositive()) {
            b.setHeartbeatTimeout(route.heartbeatTimeout());
        }
        return b.build();
    }

    /** The classified failure of a call a worker failed, or that timed out on its queue. */
    static StepFailure failure(String stepId, WorkerRoute route, Duration stepTimeout, ActivityFailure f) {
        Throwable cause = f.getCause();
        if (cause instanceof ApplicationFailure af) {
            String type = af.getType() == null || af.getType().isBlank() ? "error" : af.getType();
            return new StepFailure(stepId, type, af.getOriginalMessage(), !af.isNonRetryable());
        }
        if (cause instanceof TimeoutFailure t) {
            Duration limit = stepTimeout == null ? PortStepExecutor.DEFAULT_CALL_TIMEOUT : stepTimeout;
            if (f.getStartedEventId() <= 0 && t.getTimeoutType() != TimeoutType.TIMEOUT_TYPE_HEARTBEAT) {
                // never started: nothing polled the queue for the whole step timeout
                return new StepFailure(stepId, TIMEOUT, "Step " + stepId + " timed out after " + Durations.format(limit)
                        + ": no worker on task queue " + route.taskQueue() + " picked up the call", true);
            }
            return timeout(stepId, t, "the worker on task queue " + route.taskQueue(), limit, route.heartbeatTimeout());
        }
        String message = cause == null ? f.getOriginalMessage() : cause.getMessage() == null ? cause.toString() : cause.getMessage();
        return new StepFailure(stepId, "error", message, true);
    }

    /** A timeout of a step's call: {@value #WORKER_LOST} when the worker stopped heartbeating, {@value #TIMEOUT} otherwise. */
    static StepFailure timeout(String stepId, TimeoutFailure t, String who, Duration limit, Duration heartbeatTimeout) {
        if (t.getTimeoutType() == TimeoutType.TIMEOUT_TYPE_HEARTBEAT) {
            return new StepFailure(stepId, WORKER_LOST, "Step " + stepId + ": " + who + " stopped heartbeating"
                    + (heartbeatTimeout == null ? "" : " for " + Durations.format(heartbeatTimeout)) + " and is considered lost", true);
        }
        return new StepFailure(stepId, TIMEOUT, "Step " + stepId + " timed out" + (limit == null ? "" : " after " + Durations.format(limit))
                + (who == null ? "" : " on " + who), true);
    }
}
