package com.iaf.console.search.index.adapters.memory;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.search.index.domain.RebuildRun;
import com.iaf.console.search.index.ports.outbound.RebuildRunPort;

import reactor.core.publisher.Mono;

/** The rebuild runs of one JVM. */
public final class InMemoryRebuildRuns implements RebuildRunPort {

    private final Map<String, RebuildRun> runs = new ConcurrentHashMap<>();

    @Override
    public Mono<RebuildRun> save(RebuildRun run) {
        return Mono.fromSupplier(() -> {
            runs.put(run.runId(), run);
            return run;
        });
    }

    @Override
    public Mono<RebuildRun> find(String runId) {
        return Mono.justOrEmpty(runs.get(runId));
    }

    @Override
    public Mono<RebuildRun> findRunning() {
        return Mono.justOrEmpty(runs.values().stream().filter(RebuildRun::running).min(Comparator.comparing(RebuildRun::startedAt)));
    }

    @Override
    public Mono<RebuildRun> findByIdempotencyKey(String key) {
        return Mono.justOrEmpty(runs.values().stream().filter(r -> key.equals(r.idempotencyKey())).findFirst());
    }
}
