package com.iaf.console.bff.digests.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** The weeks a digest covers (decision 32.4): Monday to Sunday, UTC, each named by its Monday. */
public final class DigestWeeks {

    private DigestWeeks() {
    }

    /** The Monday of the week an instant falls in. */
    public static LocalDate weekOf(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The week before the one an instant falls in: what a Monday's run writes. */
    public static LocalDate weekBefore(Instant at) {
        return weekOf(at).minusWeeks(1);
    }

    /** The first instant of a week: its Monday at 00:00 UTC. */
    public static Instant start(LocalDate weekStart) {
        return weekStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** The first instant after a week: the next Monday at 00:00 UTC. */
    public static Instant end(LocalDate weekStart) {
        return start(weekStart.plusWeeks(1));
    }

    /** The Sunday a week ends on. */
    public static LocalDate lastDay(LocalDate weekStart) {
        return weekStart.plusDays(6);
    }

    /** A week from the text of its Monday, {@code yyyy-mm-dd}; a 400 when it is no date or not a Monday. */
    public static LocalDate parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a week is the date of its Monday, yyyy-mm-dd");
        }
        LocalDate date;
        try {
            date = LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("a week is the date of its Monday, yyyy-mm-dd; '" + text + "' is no date");
        }
        requireMonday(date);
        return date;
    }

    public static void requireMonday(LocalDate date) {
        if (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("a week starts on a Monday; " + date + " is a " + date.getDayOfWeek().name().toLowerCase(Locale.ROOT));
        }
    }
}
