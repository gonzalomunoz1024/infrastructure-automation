package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;

/**
 * The platform's own step calls (provider, automation, script) heartbeat while they run, so a
 * worker that dies mid-call fails the step as {@code worker-lost} within the heartbeat timeout
 * instead of at the step's timeout; {@code platform.temporal.heartbeat-timeout} and
 * {@code heartbeat-interval} set both. On the Temporal test server, where a running activity's
 * timeouts pass in real time; every test replays its history.
 */
class StepCallHeartbeatTest {

    static final String FLOW = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-slow, title: Slow, noun: thing, verb: poke, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                    pauseOnFailure: false
                - ping:
                    name: Ping
                    call: http
                    with: { method: GET, url: "https://status.example.com/health" }
                - add:
                    name: Add
                    script:
                      language: javascript
                      code: "return { sum: 1 + 2 }"
                - after:
                    name: After
                    notify: { channel: "#x", message: "after {{ $('Get').json.name }}" }
            """;

    private TemporalTestEngine t;

    @AfterEach
    void tearDown() {
        if (t != null) {
            t.close();
        }
    }

    private Job run() {
        return t.start(WorkflowYaml.parse(FLOW), Map.of("id", "vm-1042"));
    }

    private List<ActivityTaskScheduledEventAttributes> scheduled(String jobId) {
        return t.client.fetchHistory(jobId).getEvents().stream()
                .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
                .map(HistoryEvent::getActivityTaskScheduledEventAttributes)
                .toList();
    }

    private static long heartbeatSeconds(List<ActivityTaskScheduledEventAttributes> scheduled, String type) {
        return scheduled.stream().filter(a -> a.getActivityType().getName().equals(type)).findFirst()
                .map(a -> a.hasHeartbeatTimeout() ? a.getHeartbeatTimeout().getSeconds() : 0L)
                .orElseThrow(() -> new AssertionError("no " + type + " activity"));
    }

    @Test
    void aSlowProviderCallKeepsHeartbeatingPastAShortHeartbeatTimeout() {
        t = new TemporalTestEngine(calls -> TemporalTestEngine.slow(calls, "vm.get", Duration.ofSeconds(4)), temporal -> {
            temporal.setHeartbeatTimeout(Duration.ofSeconds(2));
            temporal.setHeartbeatInterval(Duration.ofMillis(300));
        }, env -> {
        });
        Job job = run();

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.job(job.id()).step("get").durationMs()).isGreaterThanOrEqualTo(4000L);
        assertThat(t.job(job.id()).step("after").output().path("message").asText()).isEqualTo("after web-1042");
        t.replay(job.id());
    }

    @Test
    void aCallWhoseWorkerStopsHeartbeatingFailsAsWorkerLost() {
        // heartbeating off stands in for a worker that hangs or dies mid-call
        t = new TemporalTestEngine(calls -> TemporalTestEngine.slow(calls, "vm.get", Duration.ofSeconds(5)), temporal -> {
            temporal.setHeartbeatTimeout(Duration.ofSeconds(2));
            temporal.setHeartbeatInterval(Duration.ZERO);
        }, env -> {
        });
        Job job = run();

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("worker-lost");
        assertThat(outcome.failedStep()).isEqualTo("get");
        assertThat(t.job(job.id()).step("get").error().retryable()).isTrue();
        t.replay(job.id());
    }

    @Test
    void theStepCallsCarryTheConfiguredHeartbeatTimeoutAndTheRecordsDoNot() {
        t = new TemporalTestEngine(calls -> calls, temporal -> temporal.setHeartbeatTimeout(Duration.ofSeconds(45)), env -> {
        });
        Job job = run();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);

        List<ActivityTaskScheduledEventAttributes> scheduled = scheduled(job.id());
        assertThat(heartbeatSeconds(scheduled, "CallProvider")).isEqualTo(45);
        assertThat(heartbeatSeconds(scheduled, "RunAutomation")).isEqualTo(45);
        assertThat(heartbeatSeconds(scheduled, "Script")).isEqualTo(45);
        assertThat(heartbeatSeconds(scheduled, "Notify")).isZero();
        assertThat(heartbeatSeconds(scheduled, "PublishStep")).isZero();
        assertThat(t.job(job.id()).step("add").output().path("sum").asInt()).isEqualTo(3);
        t.replay(job.id());
    }

    @Test
    void aZeroHeartbeatTimeoutLeavesTheCallsWithoutOne() {
        t = new TemporalTestEngine(calls -> calls, temporal -> temporal.setHeartbeatTimeout(Duration.ZERO), env -> {
        });
        Job job = run();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);

        assertThat(heartbeatSeconds(scheduled(job.id()), "CallProvider")).isZero();
        t.replay(job.id());
    }
}
