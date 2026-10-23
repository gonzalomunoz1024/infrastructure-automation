package com.iaf.console.bff.cost.domain;

import java.util.Objects;

/**
 * One rule the cost service's {@code budgets:check} answered for a create: {@code budget.exceeded}
 * (a deny: a budget with {@code blockCreates} is over) or {@code budget.forecast-over} (a warning),
 * naming the budget.
 */
public record BudgetRule(String id, String result, String message, String budgetId, String budgetName) {

    public static final String EXCEEDED = "budget.exceeded";
    public static final String FORECAST_OVER = "budget.forecast-over";
    public static final String DENY = "deny";

    public BudgetRule {
        Objects.requireNonNull(id, "id");
        result = result == null ? "warn" : result;
    }

    public boolean denies() {
        return DENY.equals(result);
    }
}
