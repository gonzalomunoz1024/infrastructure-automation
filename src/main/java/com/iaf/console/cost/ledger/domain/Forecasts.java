package com.iaf.console.cost.ledger.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

/**
 * The month-end forecast: what is spent so far plus the current run rate for the time left, with a
 * band of two standard deviations of the recent daily totals, widened by the square root of the
 * days left and never below what is already spent. Plain on purpose: a team can check it by hand.
 */
public final class Forecasts {

    public static final String METHOD = "run-rate";

    private Forecasts() {
    }

    /** The forecast in micros: month end, low, high. */
    public record Band(long monthEnd, long low, long high) {
    }

    public static Band forecast(long monthToDateMicros, long runRateMicros, Instant now, List<Long> recentDailyMicros) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Instant monthEnd = Accrual.startOf(today.withDayOfMonth(1).plusMonths(1));
        long rest = Accrual.cost(runRateMicros, now, monthEnd);
        long expected = monthToDateMicros + rest;
        double daysLeft = (monthEnd.toEpochMilli() - now.toEpochMilli()) / (double) Accrual.MILLIS_PER_DAY;
        double sd = standardDeviation(recentDailyMicros);
        long band = Math.round(2 * sd * Math.sqrt(Math.max(daysLeft, 0)));
        long low = Math.max(monthToDateMicros, expected - band);
        return new Band(expected, low, expected + band);
    }

    /** The share of the month still ahead, for a budget check: the new resources' monthly cost times this. */
    public static double remainingShare(Instant now) {
        LocalDate today = now.atOffset(ZoneOffset.UTC).toLocalDate();
        Instant end = Accrual.startOf(today.withDayOfMonth(1).plusMonths(1));
        double monthMillis = YearMonth.from(today).lengthOfMonth() * (double) Accrual.MILLIS_PER_DAY;
        return Math.max(0, end.toEpochMilli() - now.toEpochMilli()) / monthMillis;
    }

    static double standardDeviation(List<Long> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = values.stream().mapToLong(Long::longValue).average().orElse(0);
        double var = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (values.size() - 1);
        return Math.sqrt(var);
    }
}
