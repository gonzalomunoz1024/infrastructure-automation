package com.iaf.console.drift.scorecards.adapters.memory;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.drift.scorecards.domain.CountDelta;
import com.iaf.console.drift.scorecards.domain.Counts;
import com.iaf.console.drift.scorecards.domain.RuleKey;
import com.iaf.console.drift.scorecards.domain.ScoreSnapshot;
import com.iaf.console.drift.scorecards.ports.outbound.ScoreStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Counters and snapshots in memory, for the demo and the tests. */
public final class InMemoryScoreStore implements ScoreStorePort {

    private final Map<String, Map<RuleKey, Counts>> counters = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<LocalDate, ScoreSnapshot>> snapshots = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> apply(String accountId, List<CountDelta> deltas) {
        return Mono.fromRunnable(() -> {
            synchronized (this) {
                Map<RuleKey, Counts> of = counters.computeIfAbsent(accountId, k -> new TreeMap<>());
                for (CountDelta d : deltas) {
                    of.merge(d.key(), Counts.ZERO.plus(d.passed(), d.failed()), (a, b) -> a.plus(d.passed(), d.failed()));
                }
            }
        });
    }

    @Override
    public Mono<Void> replace(String accountId, Map<RuleKey, Counts> counts) {
        return Mono.fromRunnable(() -> {
            synchronized (this) {
                counters.put(accountId, new TreeMap<>(counts));
            }
        });
    }

    @Override
    public Mono<Map<RuleKey, Counts>> countsOf(AccountScope scope) {
        return Mono.fromSupplier(() -> copy(scope.accountId()));
    }

    @Override
    public Mono<Map<String, Map<RuleKey, Counts>>> countsOfAccounts(Collection<String> accountIds) {
        return Mono.fromSupplier(() -> {
            Map<String, Map<RuleKey, Counts>> out = new LinkedHashMap<>();
            accountIds.forEach(a -> out.put(a, copy(a)));
            return out;
        });
    }

    private synchronized Map<RuleKey, Counts> copy(String accountId) {
        return new TreeMap<>(counters.getOrDefault(accountId, Map.of()));
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.fromIterable(List.copyOf(counters.keySet()));
    }

    @Override
    public Mono<Void> saveSnapshot(ScoreSnapshot snapshot) {
        return Mono.fromRunnable(() -> snapshots.computeIfAbsent(snapshot.accountId(), k -> new TreeMap<>()).put(snapshot.day(), snapshot));
    }

    @Override
    public Flux<ScoreSnapshot> listSnapshots(AccountScope scope, LocalDate from) {
        return Flux.defer(() -> {
            TreeMap<LocalDate, ScoreSnapshot> of = snapshots.get(scope.accountId());
            return of == null ? Flux.empty() : Flux.fromIterable(List.copyOf(of.tailMap(from, true).values()));
        });
    }

    @Override
    public Mono<ScoreSnapshot> findLatestSnapshot(String accountId) {
        return Mono.defer(() -> {
            TreeMap<LocalDate, ScoreSnapshot> of = snapshots.get(accountId);
            return of == null || of.isEmpty() ? Mono.empty() : Mono.just(of.lastEntry().getValue());
        });
    }
}
