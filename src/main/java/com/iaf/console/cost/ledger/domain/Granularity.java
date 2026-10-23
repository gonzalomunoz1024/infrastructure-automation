package com.iaf.console.cost.ledger.domain;

import java.time.LocalDate;

/** How a report's series is cut: by day or by calendar month. */
public enum Granularity {
    DAY, MONTH;

    public static Granularity of(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("day")) {
            return DAY;
        }
        if (s.equalsIgnoreCase("month")) {
            return MONTH;
        }
        throw new IllegalArgumentException("granularity is day or month");
    }

    /** The start of the period a day falls in. */
    public LocalDate periodOf(LocalDate day) {
        return this == DAY ? day : day.withDayOfMonth(1);
    }

    public LocalDate next(LocalDate period) {
        return this == DAY ? period.plusDays(1) : period.plusMonths(1);
    }

    public String wire() {
        return name().toLowerCase();
    }
}
