package com.iaf.platform.archive.application;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.archive.domain.JobArchive;
import com.iaf.platform.archive.ports.inbound.ArchiveCommands;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Archives an ended job through the artifact store, reading it through the runs slice's query
 * port: first its ended children that have no archive, then its preview ({@code plan.json}) and
 * last its log lines ({@code logs.ndjson}), so a job whose logs are stored is archived with its
 * children. What it reads is what the API serves, secrets redacted. A job that ends again (a
 * batch whose failed rows were retried) is archived again over its earlier archive; a rewrite that
 * fails keeps the earlier one.
 */
@Slf4j
@Service
public class ArchiveCommandHandler implements ArchiveCommands {

    /** The most recent jobs of each ended status the start-up sweep looks at. */
    static final int SWEEP_LIMIT = 1000;

    /** Jobs the sweep looks at, and archives, at once. */
    static final int SWEEP_CONCURRENCY = 4;

    private final RunsQueries runs;
    private final ArtifactStorePort store;

    public ArchiveCommandHandler(RunsQueries runs, ArtifactStorePort store) {
        this.runs = runs;
        this.store = store;
    }

    @Override
    public Mono<Outcome> archive(String jobId) {
        return runs.job(jobId)
                .flatMap(job -> JobArchive.ended(job)
                        ? Flux.fromIterable(JobArchive.endedChildren(job)).concatMap(this::archiveIfMissing)
                                .then(Mono.defer(() -> write(jobId, job)))
                                .thenReturn(Outcome.ARCHIVED)
                        : Mono.just(Outcome.NOT_ENDED))
                .defaultIfEmpty(Outcome.UNKNOWN);
    }

    @Override
    public Mono<Long> archiveMissing() {
        return Flux.fromIterable(JobArchive.ENDED)
                .concatMap(status -> runs.jobs(null, status, null, SWEEP_LIMIT))
                .map(job -> job.path("id").asText(""))
                .filter(id -> !id.isBlank())
                .distinct()
                .flatMap(id -> Mono.defer(() -> archiveIfMissing(id)).onErrorResume(e -> {
                    log.warn("job {} is not archived, the next start tries again: {}", id, e.toString());
                    return Mono.empty();
                }), SWEEP_CONCURRENCY)
                .filter(outcome -> outcome == Outcome.ARCHIVED)
                .count();
    }

    /** The job's archive unless it has one already (empty then). */
    private Mono<Outcome> archiveIfMissing(String jobId) {
        return store.existsNamed(JobArchive.logsName(jobId)).flatMap(archived -> archived ? Mono.<Outcome>empty() : archive(jobId));
    }

    private Mono<Void> write(String jobId, JsonNode job) {
        Mono<Void> plan = JobArchive.preview(job)
                .map(preview -> store.putNamed(JobArchive.planName(jobId), JobArchive.json(preview)))
                .orElse(Mono.empty());
        return plan
                .then(runs.events(jobId).filter(JobArchive::isLog).collectList())
                .flatMap(lines -> store.putNamed(JobArchive.logsName(jobId), JobArchive.ndjson(lines))
                        .doOnSuccess(done -> log.debug("archived job {}: {} log lines{}", jobId, lines.size(),
                                job.has("preview") ? " and its preview" : "")));
    }
}
