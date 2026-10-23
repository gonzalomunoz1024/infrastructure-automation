package com.iaf.console.state.inventory.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** Where the platform feed was last read up to, per feed name, so a restart continues and never replays the whole feed. */
@GlobalRead("checkpoints are this service's own bookkeeping")
public interface FeedCheckpointPort {

    /** The last sequence applied; empty when the feed was never read. */
    Mono<Long> find(String feed);

    Mono<Void> save(String feed, long seq);
}
