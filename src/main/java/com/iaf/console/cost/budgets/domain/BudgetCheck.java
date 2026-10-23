package com.iaf.console.cost.budgets.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Whether a create may go ahead under the account's budgets ({@code BudgetCheck} of the contract). */
public record BudgetCheck(String decision, List<Rule> rules) {

    public static final String EXCEEDED = "budget.exceeded";
    public static final String FORECAST_OVER = "budget.forecast-over";

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Rule(String id, String result, String message, String budgetId, String budgetName, Double percent) {
    }

    /** The question: the account, the new resources' type, service and tags, and their monthly cost (0 when unknown). */
    public record Request(String accountId, String type, String service, Map<String, String> tags, java.math.BigDecimal monthly) {
    }

    public static BudgetCheck of(List<Rule> rules) {
        boolean deny = rules.stream().anyMatch(r -> "deny".equals(r.result()));
        return new BudgetCheck(deny ? "deny" : "allow", List.copyOf(rules));
    }
}
