package com.iaf.console.cost.budgets.ports.outbound;

import com.iaf.console.cost.budgets.domain.Budget;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The budgets, one record each, written by compare-and-set on {@code version}. */
public interface BudgetStorePort {

    Mono<Budget> find(AccountScope scope, String budgetId);

    /** The budget a create with this idempotency key made in the account. */
    Mono<Budget> findByIdempotencyKey(AccountScope scope, String key);

    /** A page of the account's budgets by name then id, after the cursor's {@code (name, id)}; {@code limit + 1} fetched. */
    Flux<Budget> list(AccountScope scope, String afterName, String afterId, int limit);

    /** Every budget of the account, for a check. */
    Flux<Budget> listAll(AccountScope scope);

    /** Every budget of every account, for the evaluator. */
    @GlobalRead("the evaluator walks every budget; it reads spend per budget's own account")
    Flux<Budget> listEverything();

    /** Stores a new budget; a ConflictException when its idempotency key was used in the account. */
    Mono<Budget> insert(Budget budget);

    /** Replaces the budget when the stored one is at {@code expectedVersion}; false when it moved or is gone. */
    Mono<Boolean> replace(Budget budget, long expectedVersion);

    /** Deletes the budget, at {@code expectedVersion} unless that is null; false when it moved or is gone. */
    Mono<Boolean> delete(String accountId, String budgetId, Long expectedVersion);

    /** How many budgets the account has, for its quota. */
    Mono<Long> count(AccountScope scope);
}
