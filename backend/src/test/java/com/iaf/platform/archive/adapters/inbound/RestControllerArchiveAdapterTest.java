package com.iaf.platform.archive.adapters.inbound;

import static com.iaf.platform.archive.ArchiveFixtures.detail;
import static com.iaf.platform.archive.ArchiveFixtures.history;
import static com.iaf.platform.archive.ArchiveFixtures.json;
import static com.iaf.platform.archive.ArchiveFixtures.logLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.archive.application.ArchiveQueryHandler;
import com.iaf.platform.archive.domain.JobArchive;
import com.iaf.platform.common.adapters.artifacts.FilesystemArtifactStore;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.config.ArtifactsProperties;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.identity.domain.Scopes;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class RestControllerArchiveAdapterTest {

    static final JsonNode ARCHIVED_LINE = logLine("j1", 7, "from the archive");
    static final String PREVIEW = "{\"changes\":[{\"target\":\"vm-1\",\"effect\":\"restart\"}]}";

    @TempDir
    Path dir;

    RunsQueries runs;
    ArtifactStorePort store;
    WebTestClient client;

    @BeforeEach
    void setUp() {
        runs = mock(RunsQueries.class);
        when(runs.job(anyString())).thenReturn(Mono.empty());
        when(runs.events(anyString())).thenAnswer(call -> history(call.getArgument(0)));
        ArtifactsProperties props = new ArtifactsProperties();
        props.setDir(dir.resolve("artifacts").toString());
        store = new FilesystemArtifactStore(props);
        client = WebTestClient.bindToController(new RestControllerArchiveAdapter(new ArchiveQueryHandler(runs, store)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    void archive(String jobId, byte[] logs, String plan) {
        if (plan != null) {
            store.putNamed(JobArchive.planName(jobId), plan.getBytes(StandardCharsets.UTF_8)).block();
        }
        store.putNamed(JobArchive.logsName(jobId), logs).block();
    }

    List<JsonNode> ndjson(String uri) {
        String body = client.get().uri(uri).accept(MediaType.APPLICATION_NDJSON).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .expectBody(String.class).returnResult().getResponseBody();
        return JobArchive.lines(body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anArchivedJobsLogsComeFromItsArchive() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", "")));
        archive("j1", JobArchive.ndjson(List.of(ARCHIVED_LINE)), null);

        assertThat(ndjson("/api/v1/jobs/j1/logs")).containsExactly(ARCHIVED_LINE);

        verify(runs, never()).events("j1");
    }

    @Test
    void anEndedJobNotArchivedYetServesTheLogLinesOfItsEvents() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "failed", "")));

        assertThat(ndjson("/api/v1/jobs/j1/logs")).containsExactly(logLine("j1", 3, "PLAY [restart]"), logLine("j1", 4, "ok: [vm-1]"));
    }

    @Test
    void anArchiveThatCannotBeReadFallsBackToTheEvents() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", "")));
        archive("j1", "{\"seq\":1}\nnot json\n".getBytes(StandardCharsets.UTF_8), null);

        assertThat(ndjson("/api/v1/jobs/j1/logs")).containsExactly(logLine("j1", 3, "PLAY [restart]"), logLine("j1", 4, "ok: [vm-1]"));
    }

    @Test
    void aRunningJobsLogsFollowItsEventsUntilItEnds() {
        Sinks.Many<JsonNode> events = Sinks.many().unicast().onBackpressureBuffer();
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "running", "")));
        when(runs.events("j1")).thenReturn(events.asFlux());
        archive("j1", JobArchive.ndjson(List.of(ARCHIVED_LINE)), null);
        events.tryEmitNext(logLine("j1", 3, "PLAY [restart]"));

        FluxExchangeResult<JsonNode> result = client.get().uri("/api/v1/jobs/j1/logs").accept(MediaType.APPLICATION_NDJSON).exchange()
                .expectStatus().isOk()
                .returnResult(JsonNode.class);

        StepVerifier.create(result.getResponseBody())
                .expectNext(logLine("j1", 3, "PLAY [restart]"))
                .then(() -> {
                    events.tryEmitNext(json("{\"seq\":4,\"jobId\":\"j1\",\"type\":\"step\",\"data\":{\"event\":\"job.step\"}}"));
                    events.tryEmitNext(logLine("j1", 5, "ok: [vm-1]"));
                    events.tryEmitComplete();
                })
                .expectNext(logLine("j1", 5, "ok: [vm-1]"))
                .verifyComplete();
    }

    @Test
    void aRunningJobWithoutALineYetIsAnsweredAtOnce() {
        Sinks.Many<JsonNode> events = Sinks.many().unicast().onBackpressureBuffer();
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "queued", "")));
        when(runs.events("j1")).thenReturn(events.asFlux());

        FluxExchangeResult<String> result = client.mutate().responseTimeout(Duration.ofSeconds(2)).build()
                .get().uri("/api/v1/jobs/j1/logs").accept(MediaType.APPLICATION_NDJSON).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_NDJSON)
                .returnResult(String.class);
        events.tryEmitNext(logLine("j1", 2, "waiting for a slot"));
        events.tryEmitComplete();

        StepVerifier.create(result.getResponseBody().reduce("", (body, line) -> body + line + "\n"))
                .assertNext(body -> assertThat(JobArchive.lines(body.getBytes(StandardCharsets.UTF_8))).containsExactly(logLine("j1", 2, "waiting for a slot")))
                .verifyComplete();
    }

    @Test
    void theArchivedPlanOfAnEndedJobAndThePreviewOfOneNotArchivedYet() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", ",\"preview\":" + PREVIEW)));
        archive("j1", new byte[0], "{\"changes\":[{\"target\":\"vm-1\",\"effect\":\"restart\"}],\"archived\":true}");
        when(runs.job("j2")).thenReturn(Mono.just(detail("j2", "succeeded", ",\"preview\":" + PREVIEW)));
        when(runs.job("j3")).thenReturn(Mono.just(detail("j3", "waiting-approval", ",\"preview\":" + PREVIEW)));

        client.get().uri("/api/v1/jobs/j1/plan").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.archived").isEqualTo(true).jsonPath("$.changes[0].target").isEqualTo("vm-1");
        client.get().uri("/api/v1/jobs/j2/plan").exchange().expectStatus().isOk()
                .expectBody().json(PREVIEW, JsonCompareMode.STRICT);
        client.get().uri("/api/v1/jobs/j3/plan").exchange().expectStatus().isOk()
                .expectBody().json(PREVIEW, JsonCompareMode.STRICT);
    }

    @Test
    void aJobWithoutAPreviewHasNoPlan() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", "")));
        archive("j1", new byte[0], null);

        client.get().uri("/api/v1/jobs/j1/plan").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("preview of job j1 not found");
    }

    @Test
    void anUnknownJobIsNotFound() {
        client.get().uri("/api/v1/jobs/nope/logs").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("job nope not found");
        client.get().uri("/api/v1/jobs/nope/plan").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("job nope not found");
    }

    @Test
    void aJobTheCallerMayNotSeeIsForbiddenAndItsArchiveIsNotRead() {
        when(runs.job("j1")).thenReturn(Mono.error(new ForbiddenException("you may not read job j1")));
        ArtifactStorePort untouched = mock(ArtifactStorePort.class);
        WebTestClient guarded = WebTestClient.bindToController(new RestControllerArchiveAdapter(new ArchiveQueryHandler(runs, untouched)))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();

        guarded.get().uri("/api/v1/jobs/j1/logs").exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("you may not read job j1");
        guarded.get().uri("/api/v1/jobs/j1/plan").exchange().expectStatus().isForbidden();

        verify(untouched, never()).getNamed(anyString());
        verify(runs, never()).events("j1");
    }

    @Test
    void readingTheLogsAndThePlanNeedsTheScopeOfReadingTheJob() {
        assertThat(Scopes.required("GET", "/api/v1/jobs/j1/logs", false)).isEqualTo(Scopes.JOBS_READ);
        assertThat(Scopes.required("GET", "/api/v1/jobs/j1/plan", false)).isEqualTo(Scopes.JOBS_READ);
        assertThat(Scopes.required("GET", "/api/v1/jobs/j1", false)).isEqualTo(Scopes.JOBS_READ);
        assertThat(Scopes.required("POST", "/api/v1/jobs/j1/logs", false)).isEqualTo(Scopes.ADMIN);
    }
}
