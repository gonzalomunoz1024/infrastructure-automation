package com.iaf.console.bff.cost.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names the account's policies decide cost and budgets on (decision 25). Neither is
 * regional: Cost Explorer, price lists and the chargeback are decided on everything of service
 * {@code cost} in the account, {@code wfrn:<partition>:cost::<account>:*}; budgets on
 * {@code wfrn:<partition>:budgets::<account>:budget/<id>}, the id written with a hyphen for its
 * underscore ({@code bud_01j…} is {@code budget/bud-01j…}), as a wfrn id takes no underscore.
 */
public final class CostNames {

    public static final String COST = "cost";
    public static final String BUDGETS = "budgets";
    public static final String BUDGET_KIND = "budget";

    /** A budget id as the cost service mints them. */
    public static final Pattern BUDGET_ID = Pattern.compile("^bud_[0-9a-hjkmnp-tv-z]{26}$");

    private CostNames() {
    }

    /** Everything of the cost service in an account. */
    public static String costPattern(String partition, String accountId) {
        return "wfrn:" + partition + ":" + COST + "::" + accountId + ":*";
    }

    /** Every budget of an account. */
    public static String budgetsPattern(String partition, String accountId) {
        return "wfrn:" + partition + ":" + BUDGETS + "::" + accountId + ":" + BUDGET_KIND + "/*";
    }

    /** One budget of an account. */
    public static String budget(String partition, String accountId, String budgetId) {
        return "wfrn:" + partition + ":" + BUDGETS + "::" + accountId + ":" + BUDGET_KIND + "/" + budgetId.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static boolean isBudgetId(String budgetId) {
        return budgetId != null && BUDGET_ID.matcher(budgetId).matches();
    }
}
