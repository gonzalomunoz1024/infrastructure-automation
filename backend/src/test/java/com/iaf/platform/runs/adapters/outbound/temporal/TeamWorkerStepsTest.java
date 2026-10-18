package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.workflows.domain.WorkflowYaml;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import io.temporal.activity.DynamicActivity;
import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.ActivityCompletionException;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.common.converter.EncodedValues;
import io.temporal.failure.ApplicationFailure;
import reactor.core.publisher.Mono;

/**
 * Steps a team's own worker serves, on the Temporal test server: the action of an sdk provider and
 * a {@code call: activity} run as one activity on the team's task queue, with the stable payload,
 * the step's timeout and the provider's heartbeat timeout, and their failures come back classified
 * for the interpreter's retries. A fake worker stands in for the team's; every test replays its
 * history, and a history recorded before team workers existed still replays.
 */
class TeamWorkerStepsTest {

    static final String QUEUE = "provider-reports";
    static final String ACTIVITIES = "team-x-activities";

    static final String PROVIDER = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: reports, title: Reports, owner: team-x, version: 1.0.0 }
            spec:
              type: sdk
              worker: { taskQueue: provider-reports, heartbeatTimeout: 2s }
              actions:
                - { id: report.create, title: Create a report, effect: create, sample: { id: rpt-sample } }
                - { id: report.flaky, title: Flaky, effect: create }
                - { id: report.fatal, title: Fatal, effect: create }
                - { id: report.slow, title: Slow, effect: read }
                - { id: report.silent, title: Silent, effect: read }
                - { id: report.beat, title: Beat, effect: read }
                - { id: report.hang, title: Hang, effect: read }
            """;

    static final String FLOW = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: t-team, title: Team steps, noun: report, verb: make, owner: team-x, version: 1.0.0 }
            workflow:
              do:
                - get:
                    name: Get
                    call: provider
                    with: { provider: vsphere, action: vm.get, input: { id: "{{ $input.id }}" } }
                - report:
                    name: Report
                    call: provider
                    with: { provider: reports, action: report.create, input: { title: "{{ $json.name }}" } }
                - crunch:
                    name: Crunch
                    call: activity
                    with: { taskQueue: team-x-activities, activity: crunchNumbers, input: { report: "{{ $json.id }}" } }
                - after:
                    name: After
                    notify: { channel: "#x", message: "done {{ $('Report').json.id }}" }
            """;

    private final FakeTeamWorker team = new FakeTeamWorker();
    private TemporalTestEngine t;

    @BeforeEach
    void setUp() {
        t = new TemporalTestEngine(UnaryOperator.identity(), temporal -> {
        }, env -> {
            env.newWorker(QUEUE).registerActivitiesImplementations(team);
            env.newWorker(ACTIVITIES).registerActivitiesImplementations(team);
        });
        when(t.w.providers.provider(eq("reports"))).thenReturn(Mono.just(ProviderYaml.parse(PROVIDER)));
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    /** One provider step calling {@code action}, with the given step options (retry, timeout), failing the job instead of pausing. */
    private static String oneStep(String action, String options) {
        return """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-one, title: One step, noun: report, verb: make, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - call:
                        name: Call
                        call: provider
                        with: { provider: reports, action: %s, input: { title: quarterly } }
                        pauseOnFailure: false
                %s
                """.formatted(action, options == null ? "" : options.indent(8).stripTrailing());
    }

    private Job run(String yaml) {
        return t.start(WorkflowYaml.parse(yaml), Map.of("id", "vm-1042"));
    }

    private List<ActivityTaskScheduledEventAttributes> scheduled(String jobId) {
        return t.client.fetchHistory(jobId).getEvents().stream()
                .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
                .map(HistoryEvent::getActivityTaskScheduledEventAttributes)
                .toList();
    }

    private ActivityTaskScheduledEventAttributes scheduled(String jobId, String type) {
        return scheduled(jobId).stream().filter(a -> a.getActivityType().getName().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " activity in " + jobId));
    }

    @Test
    void anSdkProvidersActionRunsOnItsWorkersQueueWithTheStablePayload() {
        Job job = run(FLOW);

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        Job done = t.job(job.id());
        assertThat(done.steps()).extracting(s -> s.status()).containsOnly(StepStatus.SUCCEEDED);
        assertThat(done.step("report").output().path("id").asText()).isEqualTo("rpt-quarterly");
        assertThat(done.step("crunch").output().path("crunched").asText()).isEqualTo("rpt-quarterly");
        assertThat(done.step("after").output().path("message").asText()).isEqualTo("done rpt-quarterly");

        JsonNode p = team.payload("report.create");
        assertThat(p.path("input").path("title").asText()).isEqualTo("web-1042");
        assertThat(p.path("step").asText()).isEqualTo("report");
        assertThat(p.path("attempt").asInt()).isEqualTo(1);
        assertThat(p.path("idempotencyKey").asText()).matches(job.id() + ":report:\\d+");
        assertThat(p.path("job").path("id").asText()).isEqualTo(job.id());
        assertThat(p.path("job").path("workflow").asText()).isEqualTo("t-team");
        assertThat(p.path("job").path("version").asText()).isEqualTo("1.0.0");
        assertThat(p.path("job").has("traceId")).isTrue();
        assertThat(p.has("environment")).isTrue();
        assertThat(p.fieldNames()).toIterable().containsExactlyInAnyOrder("input", "idempotencyKey", "job", "step", "environment", "attempt");

        ActivityTaskScheduledEventAttributes report = scheduled(job.id(), "report.create");
        assertThat(report.getTaskQueue().getName()).isEqualTo(QUEUE);
        assertThat(report.getHeartbeatTimeout().getSeconds()).isEqualTo(2);
        assertThat(report.getStartToCloseTimeout().getSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
        assertThat(report.getScheduleToCloseTimeout().getSeconds()).isEqualTo(Duration.ofMinutes(10).toSeconds());
        assertThat(report.getRetryPolicy().getMaximumAttempts()).isEqualTo(1);
        // the openapi provider's step still runs on the platform's activities, the sdk one never does
        assertThat(scheduled(job.id(), "CallProvider").getTaskQueue().getName()).isEqualTo(TemporalTestEngine.QUEUE);
        assertThat(scheduled(job.id())).filteredOn(a -> a.getActivityType().getName().equals("CallProvider")).hasSize(1);
        t.replay(job.id());
    }

    @Test
    void aCallActivityStepRunsOnTheTaskQueueItNames() {
        Job job = run(FLOW);

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.SUCCEEDED);

        JsonNode p = team.payload("crunchNumbers");
        assertThat(p.path("input").path("report").asText()).isEqualTo("rpt-quarterly");
        assertThat(p.path("step").asText()).isEqualTo("crunch");
        assertThat(p.path("idempotencyKey").asText()).startsWith(job.id() + ":crunch:");
        ActivityTaskScheduledEventAttributes crunch = scheduled(job.id(), "crunchNumbers");
        assertThat(crunch.getTaskQueue().getName()).isEqualTo(ACTIVITIES);
        assertThat(crunch.hasHeartbeatTimeout() && crunch.getHeartbeatTimeout().getSeconds() > 0).isFalse();
        assertThat(scheduled(job.id())).noneMatch(a -> a.getActivityType().getName().equals("RunAutomation"));
        t.replay(job.id());
    }

    @Test
    void aRetryableWorkerErrorIsRetriedByTheStepPolicyWithTheSameIdempotencyKey() {
        Job job = run(oneStep("report.flaky", "retry: { maxAttempts: 3 }"));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        List<JsonNode> calls = team.payloads("report.flaky");
        assertThat(calls).extracting(c -> c.path("attempt").asInt()).containsExactly(1, 2, 3);
        assertThat(calls).extracting(c -> c.path("idempotencyKey").asText()).containsOnly(calls.get(0).path("idempotencyKey").asText());
        assertThat(t.steps(job.id())).containsExactly("call:running", "call:failed", "call:running", "call:failed", "call:running", "call:succeeded");
        assertThat(t.events(job.id(), "job.step")).filteredOn(e -> e.data().path("status").asText().equals("failed"))
                .allSatisfy(e -> {
                    assertThat(e.data().path("error").path("class").asText()).isEqualTo("pool_busy");
                    assertThat(e.data().path("error").path("retryable").asBoolean()).isTrue();
                });
        t.replay(job.id());
    }

    @Test
    void aNonRetryableWorkerErrorFailsTheStepWithoutARetry() {
        Job job = run(oneStep("report.fatal", "retry: { maxAttempts: 3 }"));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("pool_exhausted");
        assertThat(outcome.failedStep()).isEqualTo("call");
        assertThat(outcome.message()).contains("no address left");
        assertThat(team.payloads("report.fatal")).hasSize(1);
        assertThat(t.job(job.id()).step("call").error().retryable()).isFalse();
        t.replay(job.id());
    }

    @Test
    void aCallThatOutlivesTheStepTimeoutFailsAsTimeout() {
        Job job = run(oneStep("report.slow", "timeout: 3s"));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("timeout");
        assertThat(scheduled(job.id(), "report.slow").getStartToCloseTimeout().getSeconds()).isEqualTo(3);
        t.replay(job.id());
    }

    @Test
    void aCallNoWorkerPicksUpFailsTheStepAfterItsTimeoutNamingTheQueue() {
        when(t.w.providers.provider(eq("nobody"))).thenReturn(Mono.just(ProviderYaml.parse("""
                apiVersion: iaf.platform/v1
                kind: Provider
                metadata: { name: nobody, title: Nobody home, owner: team-x, version: 1.0.0 }
                spec:
                  type: sdk
                  worker: { taskQueue: provider-nobody }
                  actions:
                    - { id: thing.make, title: Make a thing, effect: create }
                """)));
        // the test server keeps its clock while an activity waits for a worker, so this one waits in real time
        Job job = run(oneStep("report.create", "timeout: 3s").replace("provider: reports, action: report.create", "provider: nobody, action: thing.make"));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("timeout");
        assertThat(outcome.message()).contains("no worker on task queue provider-nobody picked up the call");
        assertThat(t.job(job.id()).step("call").error().retryable()).isTrue();
        ActivityTaskScheduledEventAttributes call = scheduled(job.id(), "thing.make");
        assertThat(call.getScheduleToCloseTimeout().getSeconds()).isEqualTo(3);
        assertThat(call.getStartToCloseTimeout().getSeconds()).isEqualTo(3);
        assertThat(team.calls).isEmpty();
        t.replay(job.id());
    }

    @Test
    void aWorkerThatStopsHeartbeatingFailsTheStepAsWorkerLost() {
        Job job = run(oneStep("report.silent", null));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("worker-lost");
        assertThat(outcome.message()).contains(QUEUE);
        assertThat(t.job(job.id()).step("call").error().retryable()).isTrue();
        t.replay(job.id());
    }

    @Test
    void aHeartbeatingWorkerOutlivesItsHeartbeatTimeout() {
        Job job = run(oneStep("report.beat", null));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.job(job.id()).step("call").output().path("beats").asInt()).isGreaterThan(5);
        t.replay(job.id());
    }

    @Test
    void cancellingTheJobCancelsTheActivityOnTheWorker() throws InterruptedException {
        Job job = run(oneStep("report.hang", null));
        assertThat(team.hanging.await(10, TimeUnit.SECONDS)).isTrue();

        t.execution.cancel(job.id(), "gonzalo").block();

        assertThat(t.result(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(team.cancelled.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(t.job(job.id()).status()).isEqualTo(JobStatus.CANCELLED);
        t.replay(job.id());
    }

    @Test
    void anActionTheSdkProviderLacksFailsBeforeAnyWorkerSeesIt() {
        Job job = run(oneStep("report.missing", null));

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("unknown_action");
        assertThat(team.calls).isEmpty();
        assertThat(scheduled(job.id())).noneMatch(a -> a.getTaskQueue().getName().equals(QUEUE));
        t.replay(job.id());
    }

    @Test
    void aHistoryRecordedBeforeTeamWorkersStillReplaysOnThePlatformsActivities() throws IOException {
        // recorded with the same FLOW and provider while the sdk step and the activity step ran on CallProvider and RunAutomation
        String json;
        try (InputStream in = getClass().getResourceAsStream("/temporal/team-steps-before-team-workers.json")) {
            assertThat(in).isNotNull();
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        WorkflowExecutionHistory history = WorkflowExecutionHistory.fromJson(json);
        assertThat(history.getEvents()).filteredOn(HistoryEvent::hasActivityTaskScheduledEventAttributes)
                .extracting(e -> e.getActivityTaskScheduledEventAttributes().getActivityType().getName())
                .contains("CallProvider", "RunAutomation")
                .doesNotContain("report.create", "crunchNumbers");

        t.replay(history);
    }

    /** The team's worker: answers by activity type and remembers every payload it got. */
    static final class FakeTeamWorker implements DynamicActivity {

        final List<Call> calls = new CopyOnWriteArrayList<>();
        final AtomicInteger flaky = new AtomicInteger();
        final CountDownLatch hanging = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(1);
        final AtomicBoolean stop = new AtomicBoolean();

        record Call(String type, JsonNode payload) {
        }

        JsonNode payload(String type) {
            return payloads(type).stream().findFirst().orElseThrow(() -> new AssertionError("the worker never ran " + type));
        }

        List<JsonNode> payloads(String type) {
            return calls.stream().filter(c -> c.type().equals(type)).map(Call::payload).toList();
        }

        @Override
        public Object execute(EncodedValues args) {
            ActivityExecutionContext ctx = Activity.getExecutionContext();
            String type = ctx.getInfo().getActivityType();
            JsonNode payload = args.get(0, JsonNode.class);
            calls.add(new Call(type, payload));
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            switch (type) {
                case "report.create" -> out.put("id", "rpt-quarterly").put("title", payload.path("input").path("title").asText());
                case "crunchNumbers" -> out.put("crunched", payload.path("input").path("report").asText());
                case "report.flaky" -> {
                    if (flaky.incrementAndGet() < 3) {
                        throw ApplicationFailure.newFailure("the pool is busy", "pool_busy");
                    }
                    out.put("id", "rpt-3");
                }
                case "report.fatal" -> throw ApplicationFailure.newNonRetryableFailure("no address left in the pool", "pool_exhausted");
                case "report.slow" -> out.put("beats", beat(ctx, Duration.ofSeconds(6)));
                case "report.silent" -> sleep(Duration.ofSeconds(5));
                case "report.beat" -> out.put("beats", beat(ctx, Duration.ofSeconds(4)));
                case "report.hang" -> {
                    hanging.countDown();
                    try {
                        beat(ctx, Duration.ofSeconds(25));
                    } catch (ActivityCompletionException e) {
                        cancelled.countDown();
                        throw e;
                    }
                }
                default -> throw ApplicationFailure.newNonRetryableFailure("no handler for " + type, "unknown_action");
            }
            return out;
        }

        private static int beat(ActivityExecutionContext ctx, Duration total) {
            long end = System.nanoTime() + total.toNanos();
            int beats = 0;
            while (System.nanoTime() < end) {
                ctx.heartbeat(beats++);
                sleep(Duration.ofMillis(200));
            }
            return beats;
        }

        private static void sleep(Duration d) {
            try {
                Thread.sleep(d.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
