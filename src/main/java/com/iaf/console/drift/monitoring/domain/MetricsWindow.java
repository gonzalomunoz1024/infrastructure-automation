package com.iaf.console.drift.monitoring.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * The range and step of a metrics read, with getResourceMetrics' defaults: without {@code to}, now;
 * without {@code from}, the 24 hours before {@code to}; without {@code step}, five minutes for a range
 * of a day or less and an hour beyond. A step is an ISO-8601 duration of at least a minute. The range
 * and the number of steps it holds are bounded, so one call cannot ask a provider for months by the
 * minute. Bad input is an IllegalArgumentException, which the API answers with a 400.
 */
public record MetricsWindow(Instant from, Instant to, Duration step) {

    /** The range without {@code from}. */
    public static final Duration DEFAULT_RANGE = Duration.ofHours(24);
    /** The finest step a read may ask for. */
    public static final Duration MIN_STEP = Duration.ofMinutes(1);
    static final Duration FINE_STEP = Duration.ofMinutes(5);
    static final Duration COARSE_STEP = Duration.ofHours(1);

    public MetricsWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(step, "step");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (step.compareTo(MIN_STEP) < 0) {
            throw new IllegalArgumentException("step is an ISO-8601 duration of at least PT1M");
        }
    }

    /** The window a caller asks for, with the contract's defaults and this service's bounds. */
    public static MetricsWindow of(String from, String to, String step, Instant now, Duration maxRange, int maxSteps) {
        Instant end = blank(to) ? now : instant(to, "to");
        Instant start = blank(from) ? end.minus(DEFAULT_RANGE) : instant(from, "from");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("from must be before to");
        }
        Duration range = Duration.between(start, end);
        Duration s = blank(step) ? defaultStep(range) : duration(step);
        if (s.compareTo(MIN_STEP) < 0) {
            throw new IllegalArgumentException("step is an ISO-8601 duration of at least PT1M");
        }
        if (maxRange != null && range.compareTo(maxRange) > 0) {
            throw new IllegalArgumentException("from and to may be at most " + maxRange.toDays() + " days apart");
        }
        long steps = range.toMillis() / s.toMillis();
        if (steps > maxSteps) {
            throw new IllegalArgumentException("the range holds " + steps + " steps of " + s + ", more than " + maxSteps + "; ask for a larger step");
        }
        return new MetricsWindow(start, end, s);
    }

    /** Five minutes for a day or less, else an hour. */
    public static Duration defaultStep(Duration range) {
        return range.compareTo(Duration.ofDays(1)) <= 0 ? FINE_STEP : COARSE_STEP;
    }

    /** The step as the contract writes it: an ISO-8601 duration such as {@code PT5M}. */
    public String stepText() {
        return step.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Instant instant(String value, String name) {
        String v = value.trim();
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (DateTimeParseException again) {
                throw new IllegalArgumentException(name + " is a date-time such as 2026-10-23T12:00:00Z");
            }
        }
    }

    private static Duration duration(String value) {
        try {
            return Duration.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("step is an ISO-8601 duration such as PT5M");
        }
    }
}
