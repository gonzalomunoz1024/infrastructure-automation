package com.iaf.console.cost.ledger.ports.outbound;

import java.time.Instant;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** How far the poller of the state service has read each account. */
@GlobalRead("the poller's own watermarks")
public interface CheckpointPort {

    Mono<Instant> find(String source);

    Mono<Void> save(String source, Instant at);
}
