package com.iaf.console.cost.budgets.adapters.memory;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.cost.budgets.ports.outbound.BudgetStorePort;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The budgets of one JVM. */
public final class InMemoryBudgets implements BudgetStorePort {

    private static final Comparator<Budget> BY_NAME = Comparator.comparing((Budget b) -> b.name().toLowerCase(Locale.ROOT)).thenComparing(Budget::budgetId);

    private final Map<String, Budget> budgets = new ConcurrentHashMap<>();

    @Override
    public Mono<Budget> find(AccountScope scope, String budgetId) {
        return Mono.justOrEmpty(budgets.get(budgetId)).filter(b -> b.accountId().equals(scope.accountId()));
    }

    @Override
    public Mono<Budget> findByIdempotencyKey(AccountScope scope, String key) {
        return Flux.fromIterable(budgets.values()).filter(b -> b.accountId().equals(scope.accountId()) && key.equals(b.idempotencyKey())).next();
    }

    @Override
    public Flux<Budget> list(AccountScope scope, String afterName, String afterId, int limit) {
        return Flux.fromStream(() -> budgets.values().stream().filter(b -> b.accountId().equals(scope.accountId()))
                .filter(b -> afterName == null || b.name().toLowerCase(Locale.ROOT).compareTo(afterName) > 0
                        || b.name().toLowerCase(Locale.ROOT).equals(afterName) && b.budgetId().compareTo(afterId) > 0)
                .sorted(BY_NAME).limit(limit + 1L));
    }

    @Override
    public Flux<Budget> listAll(AccountScope scope) {
        return Flux.fromStream(() -> budgets.values().stream().filter(b -> b.accountId().equals(scope.accountId())).sorted(BY_NAME));
    }

    @Override
    public Flux<Budget> listEverything() {
        return Flux.fromStream(() -> budgets.values().stream().sorted(Comparator.comparing(Budget::budgetId)));
    }

    @Override
    public Mono<Budget> insert(Budget budget) {
        return Mono.defer(() -> {
            synchronized (this) {
                if (budget.idempotencyKey() != null && budgets.values().stream()
                        .anyMatch(b -> b.accountId().equals(budget.accountId()) && budget.idempotencyKey().equals(b.idempotencyKey()))) {
                    return Mono.error(new ConflictException("the Idempotency-Key was used"));
                }
                budgets.put(budget.budgetId(), budget);
                return Mono.just(budget);
            }
        });
    }

    @Override
    public Mono<Boolean> replace(Budget budget, long expectedVersion) {
        return Mono.fromSupplier(() -> {
            boolean[] done = {false};
            budgets.computeIfPresent(budget.budgetId(), (k, b) -> {
                if (b.version() != expectedVersion) {
                    return b;
                }
                done[0] = true;
                return budget;
            });
            return done[0];
        });
    }

    @Override
    public Mono<Boolean> delete(String accountId, String budgetId, Long expectedVersion) {
        return Mono.fromSupplier(() -> {
            Budget b = budgets.get(budgetId);
            if (b == null || !b.accountId().equals(accountId) || expectedVersion != null && b.version() != expectedVersion) {
                return false;
            }
            return budgets.remove(budgetId, b);
        });
    }

    @Override
    public Mono<Long> count(AccountScope scope) {
        return Mono.fromSupplier(() -> budgets.values().stream().filter(b -> b.accountId().equals(scope.accountId())).count());
    }
}
