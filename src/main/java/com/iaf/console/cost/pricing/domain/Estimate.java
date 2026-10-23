package com.iaf.console.cost.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What {@code count} resources of a type with these attributes would cost a month at list prices,
 * with the one line a wizard shows ({@code Estimate} of the contract).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Estimate(String type, double monthly, double unitMonthly, String currency, String source, PriceRule rule, String detail) {

    public static final int MAX_COUNT = 10_000;

    public static Estimate of(PricingCatalog catalog, String type, Map<String, ?> attributes, int count) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count is 1 to " + MAX_COUNT);
        }
        Rate rate = catalog.price(type, attributes, null);
        double unit = Micros.toCents(rate.monthlyMicros());
        double total = Micros.toCents(Math.multiplyExact(rate.monthlyMicros(), (long) count));
        return new Estimate(type, total, unit, rate.currency(), rate.source(), rate.rule(), detail(type, rate, count));
    }

    static String detail(String type, Rate rate, int count) {
        if (!rate.priced()) {
            String why = rate.rule() == null ? "its type has no price binding"
                    : rate.rule().by() != null && rate.rule().value() == null ? "no " + rate.rule().by() + " was given"
                    : rate.rule().by() != null ? "the list has no price for " + rate.rule().by() + " " + Price.normalise(rate.rule().value())
                    : "the list has no price for it";
            return "No list price for " + type + ": " + why + ", so it counts as " + money(BigDecimal.ZERO, rate.currency());
        }
        String unit = money(Micros.toAmount(rate.monthlyMicros()), rate.currency());
        String what = rate.rule() != null && rate.rule().by() != null ? rate.rule().by() + " " + Price.normalise(rate.rule().value()) + " at " : "";
        String each = what + unit + " a month";
        if (count == 1) {
            return each + " (list prices)";
        }
        BigDecimal all = Micros.toAmount(rate.monthlyMicros()).multiply(BigDecimal.valueOf(count));
        return count + " × " + each + " = " + money(all, rate.currency()) + " a month (list prices)";
    }

    /** An amount as a page shows it: "$1,234.50", or "1,234.50 EUR" in another currency. */
    public static String money(BigDecimal amount, String currency) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.US);
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        String n = f.format(amount.setScale(2, RoundingMode.HALF_EVEN));
        return "USD".equals(currency) ? "$" + n : n + " " + currency;
    }
}
