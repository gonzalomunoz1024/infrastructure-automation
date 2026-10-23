package com.iaf.console.search.index.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The span a {@code created:} or {@code updated:} term keeps, from (inclusive) to (exclusive),
 * either end open. A term's value is a day ({@code 2026-10-01}, the whole day in UTC), a time
 * ({@code 2026-10-01T12:00Z}) or an age ({@code 30m}, {@code 24h}, {@code 7d}, {@code 2w}: that
 * long before now), compared with {@code >}, {@code >=}, {@code <} or {@code <=}, or two of them
 * as {@code a..b}, both ends kept. A day alone is that day, an age alone the time since then:
 * {@code created:7d} is the last seven days, {@code created:<30d} older than thirty.
 */
public record TimeRange(Instant from, Instant to) {

    /** Every time, and a missing one too. */
    public static final TimeRange ALL = new TimeRange(null, null);

    private static final Pattern AGE = Pattern.compile("^(\\d{1,4})([mhdw])$");
    private static final Pattern DAY = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Whether the range keeps every time, so it filters nothing. */
    public boolean unbounded() {
        return from == null && to == null;
    }

    /** Whether a time is in the range; a missing time is only in the unbounded one. */
    public boolean contains(Instant t) {
        if (t == null) {
            return unbounded();
        }
        return (from == null || !t.isBefore(from)) && (to == null || t.isBefore(to));
    }

    /** What both ranges keep. */
    public TimeRange and(TimeRange other) {
        Instant f = from == null ? other.from : other.from == null || from.isAfter(other.from) ? from : other.from;
        Instant t = to == null ? other.to : other.to == null || to.isBefore(other.to) ? to : other.to;
        return new TimeRange(f, t);
    }

    /** The span of a term's value; an {@link IllegalArgumentException} says what is wrong with it. */
    public static TimeRange parse(String value, Instant now) {
        String v = value.trim();
        int dots = v.indexOf("..");
        if (dots >= 0) {
            Point a = point(v.substring(0, dots), now);
            Point b = point(v.substring(dots + 2), now);
            return new TimeRange(a.start(), b.end());
        }
        if (v.startsWith(">=")) {
            return new TimeRange(point(v.substring(2), now).start(), null);
        }
        if (v.startsWith(">")) {
            return new TimeRange(point(v.substring(1), now).end(), null);
        }
        if (v.startsWith("<=")) {
            return new TimeRange(null, point(v.substring(2), now).end());
        }
        if (v.startsWith("<")) {
            return new TimeRange(null, point(v.substring(1), now).start());
        }
        Point p = point(v, now);
        if (p.kind() == Point.Kind.AGE) {
            return new TimeRange(p.start(), null);
        }
        if (p.kind() == Point.Kind.TIME) {
            throw new IllegalArgumentException("a time on its own needs >, >=, < or <=");
        }
        return new TimeRange(p.start(), p.end());
    }

    /** A day, a time or an age, and where it starts and ends: a whole day, a millisecond, or an instant. */
    private record Point(Kind kind, Instant start, Instant end) {
        enum Kind {
            DAY, TIME, AGE
        }
    }

    private static Point point(String s, Instant now) {
        String v = s.trim();
        Matcher age = AGE.matcher(v);
        if (age.matches()) {
            long n = Long.parseLong(age.group(1));
            Duration d = switch (age.group(2)) {
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> Duration.ofDays(7 * n);
            };
            Instant t = now.minus(d);
            return new Point(Point.Kind.AGE, t, t);
        }
        try {
            if (DAY.matcher(v).matches()) {
                Instant day = LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
                return new Point(Point.Kind.DAY, day, day.plus(Duration.ofDays(1)));
            }
            Instant t = OffsetDateTime.parse(v).toInstant();
            return new Point(Point.Kind.TIME, t, t.plusMillis(1));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("a time is a day (2026-10-01), a time (2026-10-01T12:00Z) or an age (30m, 24h, 7d, 2w)");
        }
    }
}
