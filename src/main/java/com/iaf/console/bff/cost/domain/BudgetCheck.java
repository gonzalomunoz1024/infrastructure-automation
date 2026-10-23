package com.iaf.console.bff.cost.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * The question a create asks the account's budgets before it runs: its account, the new
 * resource's type and service, its tags (a tag-scoped budget covers only what carries its tag),
 * and what it would cost a month, 0 when nobody knows.
 */
public record BudgetCheck(String accountId, String type, String service, Map<String, String> tags, BigDecimal monthly) {

    public BudgetCheck {
        Objects.requireNonNull(accountId, "accountId");
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        monthly = monthly == null || monthly.signum() < 0 ? BigDecimal.ZERO : monthly;
    }
}
