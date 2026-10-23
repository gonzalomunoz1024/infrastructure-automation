package com.iaf.console.cost.budgets.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.cost.pricing.domain.Micros;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** A budget as a create or a replace sends it ({@code BudgetInput} of the contract), and its checked form. */
public record BudgetInput(String name, BudgetScope scope, BigDecimal amount, String currency, List<Integer> thresholds, Boolean blockCreates) {

    public static final List<Integer> DEFAULT_THRESHOLDS = List.of(80, 100);
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");
    private static final Pattern TAG_KEY = Pattern.compile("^[a-z0-9-]{1,32}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    /** The checked input: trimmed name, amount in micros, thresholds sorted and unique. */
    public record Valid(String name, BudgetScope.Tag tag, long amountMicros, String currency, List<Integer> thresholds, boolean blockCreates) {
    }

    /** Checks every field, all problems at once; {@code currency} must be the price lists' currency. */
    public Valid validate(String pricedCurrency) {
        List<Problem> problems = new ArrayList<>();
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 64) {
            problems.add(Problem.error("length", "/name", "a name of 1 to 64 characters"));
        }
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            problems.add(Problem.error("range", "/amount", "an amount above 0 and at most 1,000,000,000"));
        }
        String c = currency == null || currency.isBlank() ? pricedCurrency : currency.trim();
        if (!CURRENCY.matcher(c).matches()) {
            problems.add(Problem.error("pattern", "/currency", "an ISO 4217 code"));
        } else if (!c.equals(pricedCurrency)) {
            problems.add(Problem.error("currency", "/currency", "prices are in " + pricedCurrency + "; a budget in " + c + " cannot be compared"));
        }
        List<Integer> t = thresholds == null || thresholds.isEmpty() ? DEFAULT_THRESHOLDS : thresholds;
        if (thresholds != null && (thresholds.size() > 5 || new LinkedHashSet<>(thresholds).size() != thresholds.size())) {
            problems.add(Problem.error("thresholds", "/thresholds", "1 to 5 different percentages"));
        }
        if (t.stream().anyMatch(p -> p == null || p < 1 || p > 1000)) {
            problems.add(Problem.error("range", "/thresholds", "each percentage is 1 to 1000"));
        }
        BudgetScope.Tag tag = scope == null ? null : scope.tag();
        if (tag != null && (!TAG_KEY.matcher(tag.key()).matches() || tag.value().length() > 256)) {
            problems.add(Problem.error("pattern", "/scope/tag", "a tag key of [a-z0-9-]{1,32} and a value of at most 256 characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidInputException("the budget is not valid", problems);
        }
        List<Integer> sorted = t.stream().distinct().sorted().toList();
        return new Valid(n, tag, Micros.of(amount), c, sorted, Boolean.TRUE.equals(blockCreates));
    }
}
