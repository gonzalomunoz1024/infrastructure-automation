package com.iaf.console.search.index.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.search.index.domain.Checkpoint;

import reactor.core.publisher.Mono;

/** Where each source of events was last read, for the status's lag and the poller's watermark. */
@GlobalRead("checkpoints are this service's own bookkeeping, not tenant data")
public interface CheckpointPort {

    Mono<Checkpoint> save(Checkpoint checkpoint);

    /** The checkpoint of a source, empty when it was never read. */
    Mono<Checkpoint> find(String source);
}
