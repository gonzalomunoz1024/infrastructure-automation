package com.iaf.console.bff.insights.domain;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/** The window the executive home's numbers cover: the last 7, 30 or 90 days up to now. */
public enum Period {

    P7D("7d", 7),
    P30D("30d", 30),
    P90D("90d", 90);

    public static final Period DEFAULT = P30D;

    private final String id;
    private final int days;

    Period(String id, int days) {
        this.id = id;
        this.days = days;
    }

    public String id() {
        return id;
    }

    public Duration length() {
        return Duration.ofDays(days);
    }

    /** The period a query names; 30 days when it names none, a 400 when it names another. */
    public static Period parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String wanted = value.trim().toLowerCase(Locale.ROOT);
        for (Period p : values()) {
            if (p.id.equals(wanted)) {
                return p;
            }
        }
        throw new InvalidInputException("period is 7d, 30d or 90d",
                List.of(Problem.error("period.invalid", "/period", "the period is 7d, 30d or 90d, was " + value)));
    }
}
