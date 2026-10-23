package com.iaf.console.cost.budgets.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How a budget stands in one month, as the evaluator last saw it: the amount in force, spent,
 * forecast (micros), the thresholds already alerted in the order they were crossed, and whether
 * the month is over (then {@code spent} is final and {@code forecast} equals it).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BudgetPeriod(String budgetId, String accountId, String period, long amountMicros, long spentMicros, long forecastMicros,
        List<Integer> alertsSent, Instant asOf, boolean closed) {

    public BudgetPeriod {
        Objects.requireNonNull(budgetId, "budgetId");
        Objects.requireNonNull(period, "period");
        alertsSent = alertsSent == null ? List.of() : List.copyOf(alertsSent);
    }

    public String id() {
        return budgetId + ":" + period;
    }

    public BudgetPeriod withAlerts(List<Integer> crossed) {
        List<Integer> all = new ArrayList<>(alertsSent);
        crossed.stream().filter(t -> !all.contains(t)).forEach(all::add);
        return new BudgetPeriod(budgetId, accountId, period, amountMicros, spentMicros, forecastMicros, all, asOf, closed);
    }
}
