package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.StepFailure;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.RetryState;
import io.temporal.api.enums.v1.TimeoutType;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;

class WorkerCallsTest {

    static final JobRef JOB = new JobRef("job-7f3a", "reserve-ip", "Reserve an address", "1.2.0", Job.Kind.SINGLE, null, "rest", "prod",
            Instant.parse("2026-10-17T10:00:00Z"), "4bf92f3577b34da6a3ce929d0e0e4736", null, null, false, null, null);
    static final WorkerRoute ROUTE = new WorkerRoute("provider-ip-pool", Duration.ofSeconds(30));

    static ActivityFailure failed(Throwable cause) {
        return new ActivityFailure("activity failed", 5, 6, "address.reserve", "7", RetryState.RETRY_STATE_MAXIMUM_ATTEMPTS_REACHED, "worker-1", cause);
    }

    @Test
    void thePayloadIsTheStableEnvelopeTheSdksRead() {
        JsonNode input = JsonNodeFactory.instance.objectNode().put("pool", "office");

        JsonNode p = WorkerCalls.payload(JOB, "reserve", WorkerCalls.idempotencyKey(JOB.id(), "reserve", 3), 2, input);

        assertThat(p.toString()).isEqualTo("{\"input\":{\"pool\":\"office\"},\"idempotencyKey\":\"job-7f3a:reserve:3\","
                + "\"job\":{\"id\":\"job-7f3a\",\"workflow\":\"reserve-ip\",\"version\":\"1.2.0\",\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\"},"
                + "\"step\":\"reserve\",\"environment\":\"prod\",\"attempt\":2}");
    }

    @Test
    void aMissingInputIsAnEmptyObjectAndMissingFieldsStayInTheEnvelope() {
        JobRef bare = new JobRef("job-1", "w", null, "1.0.0", null, null, null, null, null, null, null, null, false, null, null);

        JsonNode p = WorkerCalls.payload(bare, "s", "job-1:s:1", 1, null);

        assertThat(p.path("input").isObject()).isTrue();
        assertThat(p.path("input").size()).isZero();
        assertThat(p.has("environment")).isTrue();
        assertThat(p.path("environment").isNull()).isTrue();
        assertThat(p.path("job").has("traceId")).isTrue();
    }

    @Test
    void theActivityRunsOnceForTheStepsTimeoutWithTheProvidersHeartbeat() {
        ActivityOptions o = WorkerCalls.options(ROUTE, Duration.ofMinutes(3));
        assertThat(o.getTaskQueue()).isEqualTo("provider-ip-pool");
        assertThat(o.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(3));
        assertThat(o.getScheduleToCloseTimeout()).as("the wait in the queue counts too").isEqualTo(Duration.ofMinutes(3));
        assertThat(o.getHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(o.getRetryOptions().getMaximumAttempts()).isEqualTo(1);

        ActivityOptions defaults = WorkerCalls.options(new WorkerRoute("q", null), null);
        assertThat(defaults.getStartToCloseTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.getScheduleToCloseTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.getHeartbeatTimeout()).isNull();
    }

    @Test
    void aCallNoWorkerStartedTimesOutNamingTheQueueNobodyPolls() {
        ActivityFailure neverStarted = new ActivityFailure("activity timed out", 5, 0, "address.reserve", "7",
                RetryState.RETRY_STATE_TIMEOUT, "", new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE));

        StepFailure f = WorkerCalls.failure("reserve", ROUTE, Duration.ofMinutes(2), neverStarted);

        assertThat(f.errorClass()).isEqualTo("timeout");
        assertThat(f.getMessage()).isEqualTo("Step reserve timed out after 2m: no worker on task queue provider-ip-pool picked up the call");
        assertThat(f.retryable()).isTrue();
        // once a worker had it, running out of time is the plain timeout
        StepFailure late = WorkerCalls.failure("reserve", ROUTE, Duration.ofMinutes(2),
                failed(new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_SCHEDULE_TO_CLOSE)));
        assertThat(late.errorClass()).isEqualTo("timeout");
        assertThat(late.getMessage()).doesNotContain("no worker");
    }

    @Test
    void anApplicationFailuresTypeIsTheErrorClassAndNonRetryableMeansNoRetry() {
        StepFailure retryable = WorkerCalls.failure("reserve", ROUTE, null, failed(ApplicationFailure.newFailure("the pool is busy", "pool_busy")));
        assertThat(retryable.errorClass()).isEqualTo("pool_busy");
        assertThat(retryable.getMessage()).isEqualTo("the pool is busy");
        assertThat(retryable.retryable()).isTrue();
        assertThat(retryable.fatal()).isFalse();

        StepFailure fatal = WorkerCalls.failure("reserve", ROUTE, null, failed(ApplicationFailure.newNonRetryableFailure("no address left", "pool_exhausted")));
        assertThat(fatal.errorClass()).isEqualTo("pool_exhausted");
        assertThat(fatal.retryable()).isFalse();
        assertThat(fatal.step()).isEqualTo("reserve");
    }

    @Test
    void aStartToCloseTimeoutIsTimeoutAndAHeartbeatTimeoutIsWorkerLost() {
        StepFailure timeout = WorkerCalls.failure("reserve", ROUTE, Duration.ofMinutes(2),
                failed(new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_START_TO_CLOSE)));
        assertThat(timeout.errorClass()).isEqualTo("timeout");
        assertThat(timeout.getMessage()).contains("2m", "provider-ip-pool");
        assertThat(timeout.retryable()).isTrue();

        StepFailure lost = WorkerCalls.failure("reserve", ROUTE, null, failed(new TimeoutFailure("timeout", null, TimeoutType.TIMEOUT_TYPE_HEARTBEAT)));
        assertThat(lost.errorClass()).isEqualTo("worker-lost");
        assertThat(lost.getMessage()).contains("provider-ip-pool", "30s");
        assertThat(lost.retryable()).isTrue();
    }
}
