package com.iaf.platform.runs.ports.inbound;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the runs slice. Jobs are served as the API shapes in docs/api.md (JobSummary,
 * JobDetail, JobEvent, Approval); other slices use {@link #outcomes} for gates and replays.
 */
public interface RunsQueries {

    /** JobSummary rows; every filter may be null. */
    Flux<JsonNode> jobs(String workflow, String status, String parent, Integer limit);

    /** JobDetail; empty Mono when unknown. */
    Mono<JsonNode> job(String id);

    /** History first, then live JobEvents until the job ends. */
    Flux<JsonNode> events(String id);

    /** Approval rows, {@code status} may be null. */
    Flux<JsonNode> approvals(String status);

    Mono<JsonNode> approval(String id);

    /** The time-travel view: steps with input/output diffs against {@code compareTo} (null = previous run of the same workflow). */
    Mono<JsonNode> replay(String id, String compareTo);

    /** What the release gates and the replay check need about finished jobs. */
    record JobOutcome(String id, String workflow, String version, String status, Instant startedAt, Instant endedAt,
            long durationMs, String errorClass, String failedStep, JsonNode input) {
    }

    Mono<List<JobOutcome>> outcomes(String workflow, String version, Instant since);

    /** Steps of a finished job as the replay check needs them: id, status, input, output. */
    Mono<List<JsonNode>> steps(String id);
}
