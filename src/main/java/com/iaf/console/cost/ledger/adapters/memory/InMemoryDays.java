package com.iaf.console.cost.ledger.adapters.memory;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.iaf.console.cost.ledger.domain.BucketDay;
import com.iaf.console.cost.ledger.domain.CostCell;
import com.iaf.console.cost.ledger.domain.CostFilter;
import com.iaf.console.cost.ledger.domain.Granularity;
import com.iaf.console.cost.ledger.ports.outbound.DayStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The closed days of one JVM: per account, per day, its rows. */
public final class InMemoryDays implements DayStorePort {

    private final Map<String, TreeMap<LocalDate, List<BucketDay>>> byAccount = new ConcurrentHashMap<>();

    @Override
    public Mono<Void> replaceDay(String accountId, LocalDate day, List<BucketDay> rows) {
        return Mono.fromRunnable(() -> {
            TreeMap<LocalDate, List<BucketDay>> days = byAccount.computeIfAbsent(accountId, k -> new TreeMap<>());
            synchronized (days) {
                days.put(day, List.copyOf(rows));
            }
        });
    }

    @Override
    public Flux<BucketDay> listDay(AccountScope scope, LocalDate day) {
        return Flux.defer(() -> Flux.fromIterable(days(scope.accountId(), day, day.plusDays(1))));
    }

    @Override
    public Flux<CostCell> sum(AccountScope scope, LocalDate from, LocalDate to, Granularity granularity, String dimension, CostFilter filter) {
        return Flux.defer(() -> {
            Map<LocalDate, Map<String, Long>> sums = new TreeMap<>();
            for (BucketDay row : days(scope.accountId(), from, to)) {
                if (!filter.matches(row.bucket()) || row.amountMicros() == 0) {
                    continue;
                }
                String key = dimension == null ? CostCell.NONE : row.bucket().dimension(dimension);
                sums.computeIfAbsent(granularity.periodOf(row.day()), k -> new TreeMap<>())
                        .merge(key == null || key.isEmpty() ? CostCell.NONE : key, row.amountMicros(), Long::sum);
            }
            return Flux.fromIterable(sums.entrySet()).concatMapIterable(e -> e.getValue().entrySet().stream()
                    .map(g -> new CostCell(e.getKey(), g.getKey(), g.getValue())).collect(Collectors.toList()));
        });
    }

    @Override
    public Mono<Long> deleteBefore(String accountId, LocalDate day) {
        return Mono.fromSupplier(() -> {
            TreeMap<LocalDate, List<BucketDay>> days = byAccount.get(accountId);
            if (days == null) {
                return 0L;
            }
            synchronized (days) {
                Map<LocalDate, List<BucketDay>> old = days.headMap(day);
                long n = old.values().stream().mapToLong(List::size).sum();
                old.clear();
                return n;
            }
        });
    }

    private List<BucketDay> days(String accountId, LocalDate from, LocalDate to) {
        TreeMap<LocalDate, List<BucketDay>> days = byAccount.get(accountId);
        if (days == null || !from.isBefore(to)) {
            return List.of();
        }
        synchronized (days) {
            return days.subMap(from, to).values().stream().flatMap(List::stream).toList();
        }
    }
}
