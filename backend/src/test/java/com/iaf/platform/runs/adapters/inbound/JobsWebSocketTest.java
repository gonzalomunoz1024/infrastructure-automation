package com.iaf.platform.runs.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.server.adapter.WebHttpHandlerBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.config.WebSocketConfig;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.JobFactory;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.domain.Job;
import com.iaf.platform.runs.domain.JobStatus;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.testing.TestWeb;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * {@code WS /ws/jobs/{id}} over a real socket, wired as the application wires it (the slice's
 * {@code WebSocketRoute} on {@code WebSocketConfig}'s mapping): the SSE stream's events, one JSON
 * text frame each, history first.
 */
class JobsWebSocketTest {

    static final Duration WAIT = Duration.ofSeconds(10);

    private final WorkflowDefinition def = SampleContent.workflow("vm-restart");
    private final ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
    /** The jobs whose event stream the socket let go of before it ended (its client went away). */
    private final List<String> released = new CopyOnWriteArrayList<>();
    private RunsTestWiring w;
    private AnnotationConfigApplicationContext context;
    private DisposableServer server;

    @BeforeEach
    void setUp() {
        w = new RunsTestWiring();
        RunsQueryHandler queries = new RunsQueryHandler(w.projection, w.execution) {
            @Override
            public Flux<JsonNode> events(String id) {
                return super.events(id).doOnCancel(() -> released.add(id));
            }
        };
        context = new AnnotationConfigApplicationContext();
        context.registerBean(RunsQueryHandler.class, () -> queries);
        context.register(WebSocketConfig.class, JobsWebSocket.class);
        context.registerBean(WebHttpHandlerBuilder.WEB_HANDLER_BEAN_NAME, DispatcherHandler.class);
        context.refresh();
        server = HttpServer.create().host("127.0.0.1").port(0)
                .handle(new ReactorHttpHandlerAdapter(WebHttpHandlerBuilder.applicationContext(context).build()))
                .bindNow();
    }

    @AfterEach
    void tearDown() {
        server.disposeNow();
        context.close();
    }

    private URI socket(String jobId) {
        return URI.create("ws://127.0.0.1:" + server.port() + "/ws/jobs/" + jobId);
    }

    private static JsonNode parse(String text) {
        try {
            return TestWeb.json().readTree(text);
        } catch (JsonProcessingException e) {
            throw new AssertionError("a frame that is not JSON: " + text, e);
        }
    }

    private void started(String id, Instant at) {
        Job job = JobFactory.newJob(def, def.version(), SampleContent.json(Map.of("vmId", "vm-1")), SampleContent.GONZALO, "rest", null, null, null, null,
                null, at).withId(id);
        w.projection.put(job);
        w.events.started(job, null, at);
    }

    private void step(String jobId, String stepId, Instant at) {
        Step s = def.step(stepId).orElseThrow();
        JsonNode input = SampleContent.json(Map.of("provider", "vsphere"));
        w.events.step(jobId, StepEvent.running(s, 1, input, at));
        w.events.step(jobId, StepEvent.succeeded(s, 1, input, SampleContent.json(Map.of("id", "vm-1")), at.plusMillis(500), 500, null));
    }

    private void finished(String jobId, Instant at) {
        w.events.finished(jobId, "vm-restart", def.version(), JobStatus.SUCCEEDED, 1234, null, null, SampleContent.json(Map.of("vmName", "web")), null, at);
    }

    /** What the SSE twin sends for the job, as {@code JobEvent}s: {@code GET /api/v1/jobs/{id}/events}. */
    private List<JsonNode> sse(String jobId) {
        WebTestClient http = TestWeb.client(new RestControllerRunsAdapter(mock(RunsCommandHandler.class), w.queries));
        return http.get().uri("/api/v1/jobs/{id}/events", jobId).accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .returnResult(JsonNode.class).getResponseBody().collectList().block(WAIT);
    }

    @Test
    void aJobsSocketReplaysItsHistoryThenStreamsItsLiveEventsAsTheSseStreamDoesAndClosesAfterTheLastOne() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_a", t);
        step("job_a", "checkVmExists", t.plusSeconds(1));
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        AtomicReference<WebSocketSession> open = new AtomicReference<>();

        Disposable connection = client.execute(socket("job_a"), session -> {
            open.set(session);
            return session.receive().map(WebSocketMessage::getPayloadAsText).map(JobsWebSocketTest::parse).doOnNext(frames::add).then();
        }).subscribe();
        try {
            RunsTestWiring.await(WAIT, () -> frames.size() == 3);
            assertThat(frames).extracting(e -> e.path("data").path("event").asText()).containsExactly("job.started", "job.step", "job.step");

            w.events.log("job_a", "hello");
            RunsTestWiring.await(WAIT, () -> frames.size() == 4);
            assertThat(frames.get(3).path("type").asText()).isEqualTo("log");
            assertThat(frames.get(3).path("data").path("message").asText()).isEqualTo("hello");
            assertThat(frames.get(3).fieldNames()).toIterable().as("a JobEvent").containsExactly("seq", "at", "jobId", "type", "data");

            finished("job_a", t.plusSeconds(9));
            RunsTestWiring.await(WAIT, () -> frames.size() == 5);
            assertThat(frames.get(4).path("data").path("event").asText()).isEqualTo("job.finished");

            // the frames are the SSE stream's events: same JSON, same order (an ended job replays its history)
            assertThat(frames).isEqualTo(sse("job_a"));
            assertThat(frames).extracting(e -> e.path("seq").asLong()).containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(frames).allSatisfy(e -> assertThat(e.path("jobId").asText()).isEqualTo("job_a"));

            // after the job's last event the server closes the socket, as the SSE stream ends
            CloseStatus closed = open.get().closeStatus().block(WAIT);
            assertThat(closed).isNotNull();
            assertThat(closed.getCode()).as("a close frame, not a dropped connection").isNotEqualTo(1006);
            RunsTestWiring.await(WAIT, () -> !open.get().isOpen());
            assertThat(frames).hasSize(5);
            assertThat(released).as("the stream ran to its end").isEmpty();
        } finally {
            connection.dispose();
        }
    }

    @Test
    void aClientThatClosesTheSocketWhileTheJobRunsReleasesItsStreamAndWhatItSendsIsIgnored() {
        Instant t = Instant.parse("2026-10-17T10:00:00Z");
        started("job_b", t);
        List<JsonNode> frames = new CopyOnWriteArrayList<>();
        AtomicReference<WebSocketSession> open = new AtomicReference<>();

        Disposable connection = client.execute(socket("job_b"), session -> {
            open.set(session);
            Mono<Void> say = session.send(Mono.just(session.textMessage("{\"action\":\"cancel\"}")));
            Mono<Void> listen = session.receive().map(WebSocketMessage::getPayloadAsText).map(JobsWebSocketTest::parse).doOnNext(frames::add).then();
            return say.then(listen);
        }).subscribe();
        try {
            RunsTestWiring.await(WAIT, () -> frames.size() == 1);
            assertThat(frames.get(0).path("data").path("event").asText()).isEqualTo("job.started");
            w.events.log("job_b", "still running");
            RunsTestWiring.await(WAIT, () -> frames.size() == 2);
            assertThat(w.projection.get("job_b").orElseThrow().status()).as("a frame from the client steers nothing").isNotEqualTo(JobStatus.CANCELLED);

            open.get().close(CloseStatus.NORMAL).block(WAIT);

            RunsTestWiring.await(WAIT, () -> released.contains("job_b"));
            w.events.log("job_b", "nobody listens");
            assertThat(frames).hasSize(2);
        } finally {
            connection.dispose();
        }
    }

    @Test
    void aSocketForAJobThatDoesNotExistIsClosedWithoutAFrame() {
        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<CloseStatus> closed = new AtomicReference<>();

        client.execute(socket("job_none"), session -> {
            session.closeStatus().subscribe(closed::set);
            return session.receive().map(WebSocketMessage::getPayloadAsText).doOnNext(frames::add).then();
        }).onErrorComplete().block(WAIT);

        assertThat(frames).isEmpty();
        assertThat(closed.get()).isNotNull();
        // the SSE twin answers the same job with a 404
        TestWeb.client(new RestControllerRunsAdapter(mock(RunsCommandHandler.class), w.queries))
                .get().uri("/api/v1/jobs/job_none/events").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isNotFound();
    }
}
