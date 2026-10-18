package com.iaf.platform.archive.ports.inbound;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the archive slice. The job is looked up as {@code GET /jobs/{id}} looks it up, so
 * whoever may read the job may read these; an unknown job is a NotFoundException.
 */
public interface ArchiveQueries {

    /**
     * The job's log lines (JobEvents of type {@code log}): its archive once it has ended and been
     * archived, else its events' lines, which for a running job are the lines so far and then each
     * new one until it ends. The Mono fails before any line when the job is unknown or may not be
     * read, and otherwise answers at once, however long the first line takes.
     */
    Mono<Flux<JsonNode>> logs(String jobId);

    /** The preview the job was started with (its archive once it has ended); a NotFoundException when it had none. */
    Mono<JsonNode> plan(String jobId);
}
