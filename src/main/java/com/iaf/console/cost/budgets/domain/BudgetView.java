package com.iaf.console.cost.budgets.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.cost.pricing.domain.Micros;

/** A budget as the API answers it ({@code Budget} of the contract), with its month's status. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetView(String budgetId, String name, BudgetScope scope, double amount, String currency, List<Integer> thresholds,
        boolean blockCreates, long version, String createdBy, Instant createdAt, Instant updatedAt, BudgetStatus status) {

    public static BudgetView of(Budget b, BudgetStatus status) {
        return new BudgetView(b.budgetId(), b.name(), b.scope(), Micros.toCents(b.amountMicros()), b.currency(), b.thresholds(), b.blockCreates(),
                b.version(), b.createdBy(), b.createdAt(), b.updatedAt(), status);
    }
}
