package com.iaf.platform.runs.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.tracing.TestTracing;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import reactor.core.publisher.Mono;

/** {@code POST /jobs} and {@code /jobs:batch} start their job in the request's trace. */
class JobsTracingOverHttpTest {

    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String TRACEPARENT = "00-" + TRACE + "-00f067aa0ba902b7-01";

    TestTracing tracing = TestTracing.on();
    RunsCommandHandler commands = mock(RunsCommandHandler.class);
    AtomicReference<Observation> openWhileStarting = new AtomicReference<>();

    @BeforeEach
    void answers() {
        when(commands.startJob(any())).thenAnswer(inv -> {
            openWhileStarting.set(tracing.traces.recall(((StartJob) inv.getArgument(0)).traceId()));
            return Mono.just(CommandAccepted.of("job_1", "queued"));
        });
        when(commands.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    @AfterEach
    void close() {
        tracing.close();
    }

    WebTestClient traced() {
        return WebTestClient.bindToController(new RestControllerRunsAdapter(commands, mock(RunsQueryHandler.class), JobQuotas.unlimited(), tracing.traces))
                .controllerAdvice(new GlobalExceptionHandler())
                .apply(tracing.server())
                .build();
    }

    WebTestClient untraced() {
        return WebTestClient.bindToController(new RestControllerRunsAdapter(commands, mock(RunsQueryHandler.class)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    StartJob started() {
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(commands).startJob(captor.capture());
        return captor.getValue();
    }

    @Test
    void aJobStartsInTheRequestsTraceUnderItsSpan() {
        traced().post().uri("/api/v1/jobs").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of("vmId", "vm-1"))).exchange().expectStatus().isAccepted();

        assertThat(started().traceId()).isEqualTo(TRACE);
        SpanData server = tracing.spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst().orElseThrow();
        assertThat(server.getTraceId()).isEqualTo(TRACE);
        assertThat(tracing.traces.spanId(openWhileStarting.get())).isEqualTo(server.getSpanId());
    }

    @Test
    void withTracingOffTheTraceparentStillNamesTheTrace() {
        untraced().post().uri("/api/v1/jobs").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of())).exchange().expectStatus().isAccepted();
        assertThat(started().traceId()).isEqualTo(TRACE);

        untraced().post().uri("/api/v1/jobs:batch").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "items", List.of(Map.of("vmId", "a")))).exchange().expectStatus().isAccepted();
        ArgumentCaptor<StartBatch> batch = ArgumentCaptor.forClass(StartBatch.class);
        verify(commands).startBatch(batch.capture());
        assertThat(batch.getValue().traceId()).isEqualTo(TRACE);
    }

    @Test
    void aTraceIdInTheBodyWinsAndMustBeAW3cTraceId() {
        untraced().post().uri("/api/v1/jobs").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of(), "traceId", "0AF7651916CD43DD8448EB211C80319C"))
                .exchange().expectStatus().isAccepted();
        assertThat(started().traceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");

        untraced().post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of(), "traceId", "trace-1"))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(m -> assertThat((String) m).contains("W3C trace id"));
        verify(commands, times(1)).startJob(any());
    }

    @Test
    void withoutAnyTraceTheJobStartsATraceOfItsOwn() {
        untraced().post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of())).exchange().expectStatus().isAccepted();
        assertThat(started().traceId()).isNull();
    }
}
