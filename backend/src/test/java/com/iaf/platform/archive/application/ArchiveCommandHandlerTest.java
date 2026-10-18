package com.iaf.platform.archive.application;

import static com.iaf.platform.archive.ArchiveFixtures.detail;
import static com.iaf.platform.archive.ArchiveFixtures.history;
import static com.iaf.platform.archive.ArchiveFixtures.json;
import static com.iaf.platform.archive.ArchiveFixtures.logLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.iaf.platform.archive.domain.JobArchive;
import com.iaf.platform.archive.ports.inbound.ArchiveCommands.Outcome;
import com.iaf.platform.common.adapters.artifacts.FilesystemArtifactStore;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.config.ArtifactsProperties;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ArchiveCommandHandlerTest {

    @TempDir
    Path dir;

    RunsQueries runs;
    Recording store;
    ArchiveCommandHandler handler;

    /** The file store under test, recording the names this test's writes stored, in order. */
    static final class Recording implements ArtifactStorePort {
        final ArtifactStorePort delegate;
        final List<String> written = new CopyOnWriteArrayList<>();

        Recording(ArtifactStorePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Mono<Artifact> put(Path file) {
            return delegate.put(file);
        }

        @Override
        public Mono<Artifact> put(byte[] content) {
            return delegate.put(content);
        }

        @Override
        public Mono<Path> get(String sha256, Path target) {
            return delegate.get(sha256, target);
        }

        @Override
        public Mono<Boolean> exists(String sha256) {
            return delegate.exists(sha256);
        }

        @Override
        public Mono<Void> putNamed(String name, byte[] content) {
            return delegate.putNamed(name, content).doOnSuccess(done -> written.add(name));
        }

        @Override
        public Mono<byte[]> getNamed(String name) {
            return delegate.getNamed(name);
        }

        @Override
        public Mono<Boolean> existsNamed(String name) {
            return delegate.existsNamed(name);
        }
    }

    @BeforeEach
    void setUp() {
        runs = mock(RunsQueries.class);
        when(runs.job(anyString())).thenReturn(Mono.empty());
        when(runs.jobs(isNull(), anyString(), isNull(), any())).thenReturn(Flux.empty());
        when(runs.events(anyString())).thenAnswer(call -> history(call.getArgument(0)));
        ArtifactsProperties props = new ArtifactsProperties();
        props.setDir(dir.resolve("artifacts").toString());
        store = new Recording(new FilesystemArtifactStore(props));
        handler = new ArchiveCommandHandler(runs, store);
    }

    String stored(String name) throws Exception {
        return Files.readString(dir.resolve("artifacts").resolve(name), StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {"succeeded", "failed", "cancelled", "rolled-back"})
    void aJobThatEndedIsArchivedWithItsLogLinesAndItsPreview(String status) throws Exception {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", status, ",\"preview\":{\"changes\":[{\"target\":\"vm-1\",\"effect\":\"restart\"}]}")));

        StepVerifier.create(handler.archive("j1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(store.written).containsExactly("jobs/j1/plan.json", "jobs/j1/logs.ndjson");
        assertThat(stored("jobs/j1/logs.ndjson")).isEqualTo(
                "{\"seq\":3,\"at\":\"2026-10-18T10:00:03Z\",\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"event\":\"job.log\",\"jobId\":\"j1\",\"message\":\"PLAY [restart]\"}}\n"
                        + "{\"seq\":4,\"at\":\"2026-10-18T10:00:04Z\",\"jobId\":\"j1\",\"type\":\"log\",\"data\":{\"event\":\"job.log\",\"jobId\":\"j1\",\"message\":\"ok: [vm-1]\"}}\n");
        assertThat(json(stored("jobs/j1/plan.json"))).isEqualTo(json("{\"changes\":[{\"target\":\"vm-1\",\"effect\":\"restart\"}]}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"queued", "running", "waiting-approval", "needs-attention"})
    void aJobThatHasNotEndedIsNotArchived(String status) {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", status, ",\"preview\":{\"changes\":[]}")));

        StepVerifier.create(handler.archive("j1")).expectNext(Outcome.NOT_ENDED).verifyComplete();

        assertThat(store.written).isEmpty();
        verify(runs, never()).events(anyString());
    }

    @Test
    void aJobStartedWithoutAPreviewLeavesOnlyItsLogs() throws Exception {
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", "")));
        when(runs.events("j1")).thenReturn(Flux.just(json("{\"seq\":1,\"jobId\":\"j1\",\"type\":\"job\",\"data\":{}}")));

        StepVerifier.create(handler.archive("j1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(store.written).containsExactly("jobs/j1/logs.ndjson");
        assertThat(stored("jobs/j1/logs.ndjson")).as("a job without log lines has an empty archive").isEmpty();
    }

    @Test
    void aJobThatEndsAgainIsArchivedAgainOverItsEarlierArchive() throws Exception {
        when(runs.job("b1")).thenReturn(Mono.just(detail("b1", "failed", "")));
        handler.archive("b1").block();
        when(runs.events("b1")).thenReturn(history("b1").concatWith(Flux.just(logLine("b1", 6, "ana retries 1 failed row in wave 1"))));
        when(runs.job("b1")).thenReturn(Mono.just(detail("b1", "succeeded", "")));
        store.written.clear();

        StepVerifier.create(handler.archive("b1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(store.written).containsExactly("jobs/b1/logs.ndjson");
        assertThat(JobArchive.lines(stored("jobs/b1/logs.ndjson").getBytes(StandardCharsets.UTF_8)))
                .containsExactly(logLine("b1", 3, "PLAY [restart]"), logLine("b1", 4, "ok: [vm-1]"), logLine("b1", 6, "ana retries 1 failed row in wave 1"));
        verify(runs, times(2)).events("b1");
    }

    @Test
    void anUnknownJobIsUnknown() {
        StepVerifier.create(handler.archive("nope")).expectNext(Outcome.UNKNOWN).verifyComplete();
        assertThat(store.written).isEmpty();
    }

    @Test
    void theEndedChildrenWithoutAnArchiveAreArchivedBeforeTheParentWhoseLogsComeLast() {
        when(runs.job("p1")).thenReturn(Mono.just(detail("p1", "succeeded",
                ",\"children\":[{\"id\":\"c0\",\"status\":\"succeeded\"},{\"id\":\"c1\",\"status\":\"succeeded\"},"
                        + "{\"id\":\"c2\",\"status\":\"running\"},{\"id\":\"c3\",\"status\":\"failed\"}]")));
        when(runs.job("c0")).thenReturn(Mono.just(detail("c0", "succeeded", "")));
        when(runs.job("c1")).thenReturn(Mono.just(detail("c1", "succeeded", ",\"preview\":{\"changes\":[]}")));
        when(runs.job("c3")).thenReturn(Mono.just(detail("c3", "failed", "")));
        handler.archive("c0").block();
        store.written.clear();

        StepVerifier.create(handler.archive("p1")).expectNext(Outcome.ARCHIVED).verifyComplete();

        assertThat(store.written).containsExactly("jobs/c1/plan.json", "jobs/c1/logs.ndjson", "jobs/c3/logs.ndjson", "jobs/p1/logs.ndjson");
        verify(runs, never()).job("c2");
        verify(runs, times(1)).job("c0");
    }

    @Test
    void aStoreThatFailsLeavesTheJobUnarchivedSoTheNextStartTriesAgain() {
        ArtifactStorePort failing = mock(ArtifactStorePort.class);
        when(failing.existsNamed(anyString())).thenReturn(Mono.just(false));
        when(failing.putNamed(eq("jobs/j1/plan.json"), any())).thenReturn(Mono.error(new IllegalStateException("bucket unreachable")));
        when(runs.job("j1")).thenReturn(Mono.just(detail("j1", "succeeded", ",\"preview\":{\"changes\":[]}")));

        StepVerifier.create(new ArchiveCommandHandler(runs, failing).archive("j1"))
                .expectErrorMessage("bucket unreachable")
                .verify();

        verify(failing, never()).putNamed(eq("jobs/j1/logs.ndjson"), any());
    }

    @Test
    void theSweepArchivesTheEndedJobsWhoseArchiveIsMissingAndGoesOnPastAFailure() {
        when(runs.jobs(null, "succeeded", null, ArchiveCommandHandler.SWEEP_LIMIT)).thenReturn(Flux.just(
                detail("done", "succeeded", ""), detail("parent", "succeeded", ""), detail("broken", "succeeded", "")));
        when(runs.jobs(null, "cancelled", null, ArchiveCommandHandler.SWEEP_LIMIT)).thenReturn(Flux.just(detail("stopped", "cancelled", "")));
        when(runs.job("done")).thenReturn(Mono.just(detail("done", "succeeded", "")));
        when(runs.job("parent")).thenReturn(Mono.just(detail("parent", "succeeded", ",\"children\":[{\"id\":\"row-1\",\"status\":\"succeeded\"}]")));
        when(runs.job("row-1")).thenReturn(Mono.just(detail("row-1", "succeeded", "")));
        when(runs.job("broken")).thenReturn(Mono.error(new IllegalStateException("projection unavailable")));
        when(runs.job("stopped")).thenReturn(Mono.just(detail("stopped", "cancelled", "")));
        handler.archive("done").block();
        store.written.clear();

        StepVerifier.create(handler.archiveMissing()).expectNext(2L).verifyComplete();

        verify(runs, times(1)).job("done");
        assertThat(store.written).containsExactlyInAnyOrder("jobs/row-1/logs.ndjson", "jobs/parent/logs.ndjson", "jobs/stopped/logs.ndjson");
        assertThat(store.written.indexOf("jobs/row-1/logs.ndjson")).isLessThan(store.written.indexOf("jobs/parent/logs.ndjson"));
        for (String status : JobArchive.ENDED) {
            verify(runs).jobs(null, status, null, ArchiveCommandHandler.SWEEP_LIMIT);
        }
    }
}
