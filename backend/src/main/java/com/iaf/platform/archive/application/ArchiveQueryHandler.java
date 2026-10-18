package com.iaf.platform.archive.application;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.archive.domain.JobArchive;
import com.iaf.platform.archive.ports.inbound.ArchiveQueries;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.ports.ArtifactStorePort;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves an ended job's archive, and what the runs slice holds until there is one. Every read
 * first looks the job up through {@link RunsQueries#job}, as {@code GET /jobs/{id}} does, so the
 * same rule decides who sees it. An archive that cannot be read is logged and the runs slice's
 * copy is served instead.
 */
@Slf4j
@Primary
@Service
public class ArchiveQueryHandler implements ArchiveQueries {

    private final RunsQueries runs;
    private final ArtifactStorePort store;

    public ArchiveQueryHandler(RunsQueries runs, ArtifactStorePort store) {
        this.runs = runs;
        this.store = store;
    }

    @Override
    public Mono<Flux<JsonNode>> logs(String jobId) {
        return job(jobId).map(job -> JobArchive.ended(job)
                ? archivedLogs(jobId).switchIfEmpty(Flux.defer(() -> liveLogs(jobId)))
                : liveLogs(jobId));
    }

    @Override
    public Mono<JsonNode> plan(String jobId) {
        return job(jobId).flatMap(job -> (JobArchive.ended(job) ? archivedPlan(jobId) : Mono.<JsonNode>empty())
                .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(JobArchive.preview(job))))
                .switchIfEmpty(Mono.error(() -> new NotFoundException("preview of job", jobId))));
    }

    private Mono<JsonNode> job(String jobId) {
        return runs.job(jobId).switchIfEmpty(Mono.error(() -> new NotFoundException("job", jobId)));
    }

    private Flux<JsonNode> archivedLogs(String jobId) {
        return store.getNamed(JobArchive.logsName(jobId))
                .map(JobArchive::lines)
                .flatMapIterable(lines -> lines)
                .onErrorResume(e -> {
                    log.warn("the archived logs of job {} cannot be read, serving its events instead: {}", jobId, e.toString());
                    return Flux.empty();
                });
    }

    private Flux<JsonNode> liveLogs(String jobId) {
        return runs.events(jobId).filter(JobArchive::isLog);
    }

    private Mono<JsonNode> archivedPlan(String jobId) {
        return store.getNamed(JobArchive.planName(jobId))
                .map(JobArchive::parse)
                .onErrorResume(e -> {
                    log.warn("the archived plan of job {} cannot be read, serving its preview instead: {}", jobId, e.toString());
                    return Mono.empty();
                });
    }
}
