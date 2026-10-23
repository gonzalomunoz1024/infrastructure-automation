package com.iaf.console.cost.ledger.adapters.memory;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.ledger.domain.RateDelta;
import com.iaf.console.cost.ledger.ports.outbound.DeltaStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The deltas of one JVM, by id, so a second write of one keeps the first. */
public final class InMemoryDeltas implements DeltaStorePort {

    private final Map<String, RateDelta> deltas = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> insertAll(List<RateDelta> batch) {
        return Mono.fromRunnable(() -> batch.forEach(d -> deltas.putIfAbsent(d.id(), d)));
    }

    @Override
    public Flux<RateDelta> listDays(AccountScope scope, LocalDate from, LocalDate to) {
        return Flux.fromStream(() -> deltas.values().stream()
                .filter(d -> d.accountId().equals(scope.accountId()) && !d.day().isBefore(from) && d.day().isBefore(to))
                .sorted(Comparator.comparing(RateDelta::at).thenComparing(RateDelta::id)));
    }
}
