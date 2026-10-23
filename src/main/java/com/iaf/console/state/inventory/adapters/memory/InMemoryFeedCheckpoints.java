package com.iaf.console.state.inventory.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.state.inventory.ports.outbound.FeedCheckpointPort;

import reactor.core.publisher.Mono;

/** Feed checkpoints in memory: a restart of the demo reads the feed from its start again. */
public final class InMemoryFeedCheckpoints implements FeedCheckpointPort {

    private final Map<String, Long> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Mono<Long> find(String feed) {
        return Mono.justOrEmpty(checkpoints.get(feed));
    }

    @Override
    public Mono<Void> save(String feed, long seq) {
        return Mono.fromRunnable(() -> checkpoints.merge(feed, seq, Math::max));
    }
}
