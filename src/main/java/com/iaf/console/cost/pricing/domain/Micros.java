package com.iaf.console.cost.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money as whole millionths of the currency unit (micros) in a {@code long}: sums are exact and
 * order-free, so deltas can be added in any order and a day closed twice gives the same total.
 * Amounts become decimals only at the edge, rounded to cents ({@link #toAmount(long)}).
 */
public final class Micros {

    public static final long PER_UNIT = 1_000_000L;

    private Micros() {
    }

    /** A decimal amount (a price list's number) in micros, half-up. */
    public static long of(BigDecimal amount) {
        return amount.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long of(double amount) {
        return of(BigDecimal.valueOf(amount));
    }

    /** Micros as the amount the API shows: two decimals, half-up. */
    public static BigDecimal toAmount(long micros) {
        return BigDecimal.valueOf(micros).movePointLeft(6).setScale(2, RoundingMode.HALF_UP);
    }

    /** Micros as a JSON-friendly double rounded to cents. */
    public static double toCents(long micros) {
        return toAmount(micros).doubleValue();
    }
}
