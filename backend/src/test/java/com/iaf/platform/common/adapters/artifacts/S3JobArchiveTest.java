package com.iaf.platform.common.adapters.artifacts;

import static com.iaf.platform.archive.ArchiveFixtures.detail;
import static com.iaf.platform.archive.ArchiveFixtures.history;
import static com.iaf.platform.archive.ArchiveFixtures.json;
import static com.iaf.platform.archive.ArchiveFixtures.logLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.iaf.platform.archive.application.ArchiveCommandHandler;
import com.iaf.platform.archive.application.ArchiveQueryHandler;
import com.iaf.platform.archive.ports.inbound.ArchiveCommands.Outcome;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** A finished job's archive on the S3 store, against the S3 look-alike of {@link S3ArtifactStoreTest}. */
class S3JobArchiveTest {

    static final String PREVIEW = "{\"changes\":[{\"target\":\"vm-1\",\"effect\":\"restart\"}]}";

    final S3ArtifactStoreTest s3 = new S3ArtifactStoreTest();
    RunsQueries runs;
    S3ArtifactStore store;

    @BeforeEach
    void start() throws IOException {
        s3.start();
        runs = mock(RunsQueries.class);
        when(runs.job(anyString())).thenReturn(Mono.empty());
        when(runs.events(anyString())).thenAnswer(call -> history(call.getArgument(0)));
        store = s3.store(S3ArtifactStoreTest.vault());
    }

    @AfterEach
    void stop() {
        s3.stop();
    }

    @Test
    void anEndedJobIsArchivedUnderItsNamesInTheBucketAndServedFromThere() {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "rolled-back", ",\"preview\":" + PREVIEW)));

        StepVerifier.create(new ArchiveCommandHandler(runs, store).archive("j1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(s3.objects).containsOnlyKeys("/plans/artifacts/jobs/j1/plan.json", "/plans/artifacts/jobs/j1/logs.ndjson");
        assertThat(json(new String(s3.objects.get("/plans/artifacts/jobs/j1/plan.json"), StandardCharsets.UTF_8))).isEqualTo(json(PREVIEW));
        assertThat(new String(s3.objects.get("/plans/artifacts/jobs/j1/logs.ndjson"), StandardCharsets.UTF_8))
                .isEqualTo(logLine("j1", 3, "PLAY [restart]") + "\n" + logLine("j1", 4, "ok: [vm-1]") + "\n");
        assertThat(s3.seen).extracting(S3ArtifactStoreTest.Seen::method, S3ArtifactStoreTest.Seen::path).containsExactly(
                tuple("PUT", "/plans/artifacts/jobs/j1/plan.json"), tuple("PUT", "/plans/artifacts/jobs/j1/logs.ndjson"));
        assertThat(s3.seen).extracting(s -> s.header("Content-Type")).containsExactly("application/json", "application/x-ndjson");

        ArchiveQueryHandler queries = new ArchiveQueryHandler(runs, store);
        when(runs.events("j1")).thenReturn(Flux.error(new AssertionError("the events are not read once archived")));
        StepVerifier.create(queries.logs("j1").flatMapMany(lines -> lines))
                .expectNext(logLine("j1", 3, "PLAY [restart]"), logLine("j1", 4, "ok: [vm-1]"))
                .verifyComplete();
        StepVerifier.create(queries.plan("j1")).expectNext(json(PREVIEW)).verifyComplete();
    }

    @Test
    void aJobThatEndsAgainReplacesItsArchiveAndTheSweepLeavesAnArchivedJobAlone() {
        when(runs.job("b1")).thenReturn(Mono.just(detail("b1", "failed", "")));
        when(runs.jobs(isNull(), anyString(), isNull(), any())).thenReturn(Flux.empty());
        when(runs.jobs(null, "failed", null, 1000)).thenReturn(Flux.just(detail("b1", "failed", "")));
        ArchiveCommandHandler handler = new ArchiveCommandHandler(runs, store);
        handler.archive("b1").block();
        when(runs.events("b1")).thenReturn(history("b1").concatWith(Flux.just(logLine("b1", 6, "ana retries 1 failed row in wave 1"))));

        StepVerifier.create(handler.archive("b1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(new String(s3.objects.get("/plans/artifacts/jobs/b1/logs.ndjson"), StandardCharsets.UTF_8))
                .endsWith(logLine("b1", 6, "ana retries 1 failed row in wave 1") + "\n");
        s3.seen.clear();

        StepVerifier.create(handler.archiveMissing()).expectNext(0L).verifyComplete();

        assertThat(s3.seen).extracting(S3ArtifactStoreTest.Seen::method).containsExactly("HEAD");
    }
}
