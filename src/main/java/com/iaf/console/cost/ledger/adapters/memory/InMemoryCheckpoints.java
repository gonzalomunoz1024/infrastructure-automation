package com.iaf.console.cost.ledger.adapters.memory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.ledger.ports.outbound.CheckpointPort;

import reactor.core.publisher.Mono;

/** The poller's watermarks in one JVM. */
public final class InMemoryCheckpoints implements CheckpointPort {

    private final Map<String, Instant> marks = new ConcurrentHashMap<>();

    @Override
    public Mono<Instant> find(String source) {
        return Mono.justOrEmpty(marks.get(source));
    }

    @Override
    public Mono<Void> save(String source, Instant at) {
        return Mono.fromRunnable(() -> marks.put(source, at));
    }
}
