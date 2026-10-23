package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.iaf.console.bff.common.errors.CodedConflictException;

/**
 * A create refused because a budget of its scope is over and blocks creates: 409
 * {@code budget.exceeded}, with the budgets that refuse it ({@code detail.budgets}: budgetId,
 * name, message), as the preview's deny rule names them.
 */
public class BudgetExceededException extends CodedConflictException {

    private static final long serialVersionUID = 1L;

    public static final String CODE = "budget.exceeded";

    public BudgetExceededException(String message, Map<String, Object> detail) {
        super(CODE, message, detail);
    }

    /** The refusal of the budgets that deny, each {@code { budgetId, name, message }}. */
    public static BudgetExceededException of(List<Map<String, Object>> budgets) {
        String message = budgets.isEmpty() ? "a budget of this create's scope is over and blocks creates"
                : String.valueOf(budgets.get(0).getOrDefault("message", "a budget of this create's scope is over and blocks creates"));
        return new BudgetExceededException(message, Map.of("budgets", budgets));
    }
}
