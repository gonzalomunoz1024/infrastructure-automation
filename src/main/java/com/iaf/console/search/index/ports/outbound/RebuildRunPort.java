package com.iaf.console.search.index.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.search.index.domain.RebuildRun;

import reactor.core.publisher.Mono;

/** The rebuild runs, so a run survives the request that started it and a second start is refused while one runs. */
@GlobalRead("rebuild runs are this service's own bookkeeping, not tenant data")
public interface RebuildRunPort {

    Mono<RebuildRun> save(RebuildRun run);

    Mono<RebuildRun> find(String runId);

    /** The run in progress, if any. */
    Mono<RebuildRun> findRunning();

    /** The run an Idempotency-Key started, so a retried request gets the same run. */
    Mono<RebuildRun> findByIdempotencyKey(String key);
}
