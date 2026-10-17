package com.iaf.platform.audit.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Which records a listing or an export pack covers: by workflow, by the time the job was
 * requested ({@code from} inclusive, {@code to} exclusive) and by requester. Every field may be null.
 */
public record EvidenceFilter(String workflow, Instant from, Instant to, String requester) {

    public static final EvidenceFilter ALL = new EvidenceFilter(null, null, null, null);

    /** From query parameters: instants ({@code 2026-10-17T10:00:00Z}) or dates ({@code 2026-10-17}, a whole day). */
    public static EvidenceFilter parse(String workflow, String from, String to, String requester) {
        return new EvidenceFilter(blankToNull(workflow), instant(from, false, "from"), instant(to, true, "to"), blankToNull(requester));
    }

    public boolean matches(EvidenceRecord record) {
        if (workflow != null && !workflow.equals(record.workflow())) {
            return false;
        }
        if (requester != null && !requester.equals(record.requester())) {
            return false;
        }
        Instant at = record.at();
        if (from != null && (at == null || at.isBefore(from))) {
            return false;
        }
        return to == null || (at != null && at.isBefore(to));
    }

    private static Instant instant(String value, boolean endOfDay, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        try {
            if (v.length() == 10) {
                LocalDate day = LocalDate.parse(v);
                return (endOfDay ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return Instant.parse(v);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be an ISO date or instant, not '" + value + "'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
