package com.iaf.console.cost.ledger.domain;

import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * How a monthly rate becomes money: by the millisecond, at {@code monthly / days in the month} a
 * day, so a resource that lives through a whole month costs exactly its monthly rate whatever the
 * month's length. Sums are kept as exact numerators (rate × milliseconds) and divided once, so a
 * day's total does not depend on how many changes it had.
 */
public final class Accrual {

    public static final long MILLIS_PER_DAY = 86_400_000L;

    private Accrual() {
    }

    /** The exact numerator of a rate over a span: monthly micros × milliseconds. */
    public static BigInteger numerator(long monthlyMicros, long millis) {
        return BigInteger.valueOf(monthlyMicros).multiply(BigInteger.valueOf(millis));
    }

    /** A numerator accrued within one day as micros, half-up. */
    public static long toMicros(BigInteger numerator, LocalDate day) {
        BigInteger denominator = BigInteger.valueOf(YearMonth.from(day).lengthOfMonth()).multiply(BigInteger.valueOf(MILLIS_PER_DAY));
        return new java.math.BigDecimal(numerator).divide(new java.math.BigDecimal(denominator), 0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * What a monthly rate costs between two instants: the milliseconds of each calendar month
     * priced at that month's length and divided once per month, so a whole month is exactly the
     * monthly rate.
     */
    public static long cost(long monthlyMicros, Instant from, Instant to) {
        if (monthlyMicros == 0 || !to.isAfter(from)) {
            return 0;
        }
        long total = 0;
        Instant t = from;
        while (t.isBefore(to)) {
            LocalDate first = t.atOffset(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
            Instant end = startOf(first.plusMonths(1));
            Instant until = end.isBefore(to) ? end : to;
            total += toMicros(numerator(monthlyMicros, until.toEpochMilli() - t.toEpochMilli()), first);
            t = until;
        }
        return total;
    }

    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
