package com.iaf.platform.runs.domain;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations as the files spell them: ISO 8601 ({@code PT5M}, {@code P1D}) or shorthand ({@code 30m}, {@code 4h}, {@code 2d}, {@code 500ms}). */
public final class Durations {

    private static final Pattern SHORT = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");

    private Durations() {
    }

    /** Parse a duration; null or blank yields {@code dflt}; anything unparseable is an IllegalArgumentException. */
    public static Duration parse(String text, Duration dflt) {
        if (text == null || text.isBlank()) {
            return dflt;
        }
        String t = text.trim();
        Matcher m = SHORT.matcher(t);
        if (m.matches()) {
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                default -> Duration.ofDays(n);
            };
        }
        try {
            return Duration.parse(t.toUpperCase());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("'" + text + "' is not a duration; use 30m, 4h or PT30M");
        }
    }

    public static Duration parse(String text) {
        return parse(text, null);
    }

    /** A readable form for messages: 4h, 30m, 2d, 45s. */
    public static String format(Duration d) {
        if (d == null) {
            return null;
        }
        if (d.toMillis() % 86_400_000L == 0) {
            return d.toDays() + "d";
        }
        if (d.toMillis() % 3_600_000L == 0) {
            return d.toHours() + "h";
        }
        if (d.toMillis() % 60_000L == 0) {
            return d.toMinutes() + "m";
        }
        if (d.toMillis() % 1000L == 0) {
            return d.toSeconds() + "s";
        }
        return d.toMillis() + "ms";
    }
}
