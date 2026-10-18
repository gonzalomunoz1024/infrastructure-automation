package com.iaf.platform.runs.adapters.outbound.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderChecks;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.providers.domain.ProviderType;
import com.iaf.platform.providers.domain.ProviderYaml;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.Outcome;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.provider.sdk.Action;
import com.iaf.provider.sdk.ActionContext;
import com.iaf.provider.sdk.Effect;
import com.iaf.provider.sdk.Provider;
import com.iaf.provider.sdk.ProviderWorker;

import io.temporal.api.history.v1.ActivityTaskScheduledEventAttributes;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.worker.WorkerFactory;
import reactor.core.publisher.Mono;

/**
 * The platform and a team's worker built with the Java provider SDK ({@code sdk/java}), on the
 * Temporal test server: the provider file the SDK writes passes the platform's checks and is what
 * the platform reads; the interpreter schedules the provider's actions on the SDK worker's task
 * queue; what the handlers return, throw and heartbeat comes back the way the step's policy
 * expects. The SDK worker has its own client with Temporal's default data converter, as a team's
 * worker would. Every job replays.
 */
class SdkWorkerInteropTest {

    record NewNote(String title) {
    }

    record Note(String id, String title, String key, int attempt) {
    }

    record Scan(int beats) {
    }

    /** {@code action key attempt} of every call a handler saw. */
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Provider notes = Provider.named("notes")
            .title("Notes")
            .owner("team-x")
            .version("1.0.0")
            .heartbeatTimeout(Duration.ofSeconds(1))
            .alerts("#team-x")
            .error("store_busy", true, "The note store is busy")
            .error("quota_exceeded", false, "The team has no notes left")
            .action(Action.of("note.create", NewNote.class, Note.class, this::create).title("Create a note").effect(Effect.CREATE)
                    .sample(Map.of("id", "n-{{ $job.seq }}", "title", "{{ $input.title }}", "key", "sample", "attempt", 1)))
            .action(Action.of("note.flaky", NewNote.class, Note.class, (ctx, in) -> {
                if (ctx.attempt() < 3) {
                    seen(ctx);
                    throw ctx.retryable("store_busy", "The note store is busy");
                }
                return create(ctx, in);
            }).title("Create a note on a busy store").effect(Effect.CREATE))
            .action(Action.of("note.fatal", NewNote.class, Note.class, (ctx, in) -> {
                seen(ctx);
                throw ctx.fatal("quota_exceeded", "No notes left for team-x");
            }).title("Create a note over quota").effect(Effect.CREATE))
            .action(Action.of("note.scan", NewNote.class, Scan.class, (ctx, in) -> {
                seen(ctx);
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(100);
                    ctx.heartbeat(i);
                }
                return new Scan(25);
            }).title("Scan the notes").effect(Effect.READ))
            .build();

    private TemporalTestEngine t;
    private WorkerFactory team;

    private Note create(ActionContext ctx, NewNote in) {
        seen(ctx);
        return new Note("n-1", in.title(), ctx.idempotencyKey(), ctx.attempt());
    }

    private void seen(ActionContext ctx) {
        seen.add(ctx.action() + " " + ctx.idempotencyKey() + " " + ctx.attempt());
    }

    @BeforeEach
    void setUp() {
        t = new TemporalTestEngine(UnaryOperator.identity(), temporal -> {
        }, env -> {
            // the team's worker: its own client, Temporal's default data converter
            WorkflowClient client = WorkflowClient.newInstance(env.getWorkflowServiceStubs(),
                    WorkflowClientOptions.newBuilder().setNamespace(env.getNamespace()).build());
            team = WorkerFactory.newInstance(client);
            ProviderWorker.attach(team.newWorker(notes.taskQueue()), notes);
            team.start();
        });
        when(t.w.providers.provider(eq("notes"))).thenReturn(Mono.just(ProviderYaml.parse(notes.toYaml())));
    }

    @AfterEach
    void tearDown() {
        team.shutdownNow();
        t.close();
    }

    private Job run(String action, String options) {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-notes, title: Notes, noun: note, verb: create, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - call:
                        name: Call
                        call: provider
                        with: { provider: notes, action: %s, input: { title: quarterly } }
                        pauseOnFailure: false
                %s
                """.formatted(action, options == null ? "" : options.indent(8).stripTrailing());
        return t.start(WorkflowYaml.parse(yaml), Map.of());
    }

    private ActivityTaskScheduledEventAttributes scheduled(String jobId, String type) {
        return t.client.fetchHistory(jobId).getEvents().stream()
                .filter(HistoryEvent::hasActivityTaskScheduledEventAttributes)
                .map(HistoryEvent::getActivityTaskScheduledEventAttributes)
                .filter(a -> a.getActivityType().getName().equals(type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " activity in " + jobId));
    }

    @Test
    void theProviderFileTheSdkWritesPassesThePlatformsChecks() {
        String yaml = notes.toYaml();

        ProviderChecks.Result checks = ProviderChecks.check(yaml);
        ProviderDefinition def = ProviderYaml.parse(yaml);

        assertThat(checks.errors()).isEmpty();
        assertThat(checks.problems()).isEmpty();
        assertThat(def.type()).isEqualTo(ProviderType.SDK);
        assertThat(def.taskQueue()).isEqualTo("provider-notes");
        assertThat(def.spec().worker().heartbeatTimeout()).isEqualTo("1s");
        assertThat(def.actions()).extracting(ProviderDefinition.Action::id).containsExactly("note.create", "note.flaky", "note.fatal", "note.scan");
        assertThat(def.actions().get(0).input().path("required").toString()).isEqualTo("[\"title\"]");
    }

    @Test
    void aStepRunsOnTheSdkWorkerAndGetsTheHandlersOutput() {
        Job job = run("note.create", null);

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        JsonNode note = t.job(job.id()).step("call").output();
        assertThat(note.path("title").asText()).isEqualTo("quarterly");
        assertThat(note.path("key").asText()).matches(job.id() + ":call:\\d+");
        assertThat(note.path("attempt").asInt()).isEqualTo(1);
        assertThat(seen).containsExactly("note.create " + note.path("key").asText() + " 1");
        assertThat(scheduled(job.id(), "note.create").getTaskQueue().getName()).isEqualTo("provider-notes");
        t.replay(job.id());
    }

    @Test
    void aRetryableErrorIsRetriedByTheStepPolicyWithTheSameIdempotencyKey() {
        Job job = run("note.flaky", "retry: { maxAttempts: 3 }");

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(seen).hasSize(3);
        String key = seen.get(0).split(" ")[1];
        assertThat(seen).containsExactly("note.flaky " + key + " 1", "note.flaky " + key + " 2", "note.flaky " + key + " 3");
        assertThat(t.events(job.id(), "job.step")).filteredOn(e -> e.data().path("status").asText().equals("failed"))
                .hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.data().path("error").path("class").asText()).isEqualTo("store_busy");
                    assertThat(e.data().path("error").path("retryable").asBoolean()).isTrue();
                });
        t.replay(job.id());
    }

    @Test
    void aFatalErrorFailsTheStepWithoutARetry() {
        Job job = run("note.fatal", "retry: { maxAttempts: 3 }");

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("quota_exceeded");
        assertThat(outcome.message()).contains("No notes left for team-x");
        assertThat(seen).hasSize(1);
        assertThat(t.job(job.id()).step("call").error().retryable()).isFalse();
        t.replay(job.id());
    }

    @Test
    void aHeartbeatingHandlerOutlivesTheProvidersHeartbeatTimeout() {
        Job job = run("note.scan", null);

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(t.job(job.id()).step("call").output().path("beats").asInt()).isEqualTo(25);
        assertThat(scheduled(job.id(), "note.scan").getHeartbeatTimeout().getSeconds()).isEqualTo(1);
        t.replay(job.id());
    }

    @Test
    void anInputTheActionsSchemaRefusesFailsTheStepForGood() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: t-notes, title: Notes, noun: note, verb: create, owner: team-x, version: 1.0.0 }
                workflow:
                  do:
                    - call:
                        name: Call
                        call: provider
                        with: { provider: notes, action: note.create, input: { name: quarterly } }
                        pauseOnFailure: false
                        retry: { maxAttempts: 3 }
                """;
        Job job = t.start(WorkflowYaml.parse(yaml), Map.of());

        Outcome outcome = t.result(job.id());

        assertThat(outcome.status()).isEqualTo(JobStatus.FAILED);
        assertThat(outcome.errorClass()).isEqualTo("invalid_input");
        assertThat(seen).isEmpty();
        t.replay(job.id());
    }
}
