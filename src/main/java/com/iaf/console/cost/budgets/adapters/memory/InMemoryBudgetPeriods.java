package com.iaf.console.cost.budgets.adapters.memory;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.cost.budgets.ports.outbound.BudgetPeriodStorePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The budgets' months in one JVM. */
public final class InMemoryBudgetPeriods implements BudgetPeriodStorePort {

    private final Map<String, BudgetPeriod> periods = new ConcurrentHashMap<>();

    @Override
    public Mono<BudgetPeriod> find(AccountScope scope, String budgetId, String period) {
        return Mono.justOrEmpty(periods.get(budgetId + ":" + period)).filter(p -> p.accountId().equals(scope.accountId()));
    }

    @Override
    public Flux<BudgetPeriod> list(AccountScope scope, String budgetId, String beforePeriod, int limit) {
        return Flux.fromStream(() -> periods.values().stream()
                .filter(p -> p.budgetId().equals(budgetId) && p.accountId().equals(scope.accountId()))
                .filter(p -> beforePeriod == null || p.period().compareTo(beforePeriod) < 0)
                .sorted(Comparator.comparing(BudgetPeriod::period).reversed()).limit(limit + 1L));
    }

    @Override
    public Mono<Void> save(BudgetPeriod period) {
        return Mono.fromRunnable(() -> periods.put(period.id(), period));
    }

    @Override
    public Mono<Void> deleteAll(String accountId, String budgetId) {
        return Mono.fromRunnable(() -> periods.values().removeIf(p -> p.budgetId().equals(budgetId) && p.accountId().equals(accountId)));
    }
}
