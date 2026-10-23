package com.iaf.console.cost.budgets.ports.outbound;

import com.iaf.console.cost.budgets.domain.BudgetPeriod;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** How each budget stood month by month. */
public interface BudgetPeriodStorePort {

    Mono<BudgetPeriod> find(AccountScope scope, String budgetId, String period);

    /** The budget's periods newest first, before {@code beforePeriod} when it is set; {@code limit + 1} fetched. */
    Flux<BudgetPeriod> list(AccountScope scope, String budgetId, String beforePeriod, int limit);

    Mono<Void> save(BudgetPeriod period);

    /** Removes a deleted budget's periods. */
    Mono<Void> deleteAll(String accountId, String budgetId);
}
