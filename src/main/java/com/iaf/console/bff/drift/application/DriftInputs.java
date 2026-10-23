package com.iaf.console.bff.drift.application;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Pattern;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * The checks the drift routes make on what the browser sends before anything is asked of the
 * policies or the drift service: enums, times, durations, lengths and ids, each a 400 that names
 * the field, so a mistyped filter never reaches the drift service as something it reads otherwise.
 */
final class DriftInputs {

    static final List<String> FINDING_STATUSES = List.of("open", "closed", "all");
    static final List<String> SEVERITIES = List.of("info", "warning", "critical");
    static final List<String> OWNERS = List.of("any", "mine", "unknown");
    static final List<String> ALARM_STATES = List.of("ok", "alarm", "insufficient");
    static final List<String> KINDS = List.of("idle", "oversized");
    static final List<String> RECOMMENDATION_STATUSES = List.of("open", "applied", "dismissed", "superseded");
    static final int MAX_REASON = 1000;
    static final int MAX_NOTE = 500;
    static final int MAX_ID = 128;
    static final Duration MIN_STEP = Duration.ofMinutes(1);
    /** The alarm ids the web app picks. */
    static final Pattern ALARM_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    /** A console account id. */
    static final Pattern ACCOUNT_ID = Pattern.compile("^[0-9]{12}$");

    private DriftInputs() {
    }

    /** One of the values a filter takes, or null when none is given. */
    static String oneOf(String field, String value, List<String> allowed) {
        String v = blankToNull(value);
        if (v != null && !allowed.contains(v)) {
            throw invalid("enum", field, field + " is one of: " + String.join(", ", allowed));
        }
        return v;
    }

    /** A date-time with its offset ({@code 2026-10-23T12:00:00Z}), or null when none is given. */
    static Instant instant(String field, String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(v);
            } catch (DateTimeParseException again) {
                throw invalid("date-time", field, field + " is a date-time with its offset, such as 2026-10-23T12:00:00Z");
            }
        }
    }

    /** An ISO-8601 duration of at least a minute ({@code PT5M}), or null when none is given. */
    static Duration step(String field, String value) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        Duration step;
        try {
            step = Duration.parse(v);
        } catch (DateTimeParseException e) {
            throw invalid("duration", field, field + " is an ISO-8601 duration, such as PT5M");
        }
        if (step.compareTo(MIN_STEP) < 0) {
            throw invalid("range", field, field + " is at least PT1M");
        }
        return step;
    }

    /** Free text of at most {@code max} characters, or null when none is given. */
    static String text(String field, String value, int max) {
        String v = blankToNull(value);
        if (v != null && v.length() > max) {
            throw invalid("length", field, field + " is at most " + max + " characters");
        }
        return v;
    }

    /** A value the call cannot go without. */
    static String required(String field, String value) {
        String v = blankToNull(value);
        if (v == null) {
            throw invalid("required", field, field + " is required");
        }
        return v;
    }

    /** An id in a path, short and without anything that would read as another path. */
    static String id(String field, String value) {
        String v = required(field, value);
        if (v.length() > MAX_ID || v.contains("/")) {
            throw invalid("format", field, field + " is not one the drift service issued");
        }
        return v;
    }

    /** A window whose start comes before its end (the end is now when left out). */
    static void window(String sinceField, Instant since, String untilField, Instant until, Instant now) {
        Instant end = until == null ? now : until;
        if (since != null && !since.isBefore(end)) {
            throw invalid("range", sinceField, sinceField + " comes before " + (until == null ? "now" : untilField));
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static InvalidInputException invalid(String code, String field, String message) {
        return new InvalidInputException(message, List.of(Problem.error(code, "/" + field, message)));
    }
}
