package com.iaf.console.search.index.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.search.index.domain.Checkpoint;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;

import reactor.core.publisher.Mono;

/** The checkpoints of one JVM. */
public final class InMemoryCheckpoints implements CheckpointPort {

    private final Map<String, Checkpoint> checkpoints = new ConcurrentHashMap<>();

    @Override
    public Mono<Checkpoint> save(Checkpoint checkpoint) {
        return Mono.fromSupplier(() -> {
            checkpoints.put(checkpoint.source(), checkpoint);
            return checkpoint;
        });
    }

    @Override
    public Mono<Checkpoint> find(String source) {
        return Mono.justOrEmpty(checkpoints.get(source));
    }
}
