package com.iaf.console.cost.pricing.domain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * One price of a price list, as {@code kind: PriceList} writes it: a provider's action and either a
 * flat monthly amount or one per value of the attribute {@code by} names
 * ({@code monthly: { 2: 38.5, 4: 77 }}). Values are matched as text, so {@code 4}, {@code "4"} and
 * {@code 4.0} pick the same price.
 */
public record Price(String provider, String action, String by, BigDecimal flat, Map<String, BigDecimal> perValue) {

    public Price {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(action, "action");
        perValue = perValue == null ? Map.of() : Map.copyOf(new TreeMap<>(perValue));
        if ((flat == null) == perValue.isEmpty()) {
            throw new IllegalArgumentException("a price of " + provider + " " + action + " is either flat or per value of `by`, not both or neither");
        }
        if (!perValue.isEmpty() && (by == null || by.isBlank())) {
            throw new IllegalArgumentException("a price per value needs `by` (" + provider + " " + action + ")");
        }
    }

    public static Price flat(String provider, String action, BigDecimal monthly) {
        return new Price(provider, action, null, monthly, null);
    }

    public static Price perValue(String provider, String action, String by, Map<String, BigDecimal> monthly) {
        return new Price(provider, action, by, null, monthly);
    }

    public boolean flatRate() {
        return flat != null;
    }

    /** The monthly amount for a value of {@code by}; null when the price has none for it. */
    public BigDecimal monthlyFor(Object value) {
        if (flatRate()) {
            return flat;
        }
        String key = normalise(value);
        return key == null ? null : perValue.get(key);
    }

    /** A value as the text a price map is keyed by: integral numbers without a fraction. */
    public static String normalise(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            BigDecimal d = new BigDecimal(n.toString()).stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            BigDecimal d = new BigDecimal(text).stripTrailingZeros();
            return d.scale() <= 0 ? d.toBigInteger().toString() : d.toPlainString();
        } catch (NumberFormatException e) {
            return text;
        }
    }
}
