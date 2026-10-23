package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Findings opened and closed in a window, by resolution and severity, with the mean time to close:
 * the drift numbers of the executive home ({@code found} is {@code opened}, {@code fixed} is
 * {@code closed.total}, {@code open} is {@code open}). {@code accountId} is absent when the
 * console-internal principal counted every account; {@code meanMinutesToClose} when nothing closed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FindingStats(
        String accountId,
        Instant since,
        Instant until,
        int opened,
        Closed closed,
        int open,
        Map<String, Integer> bySeverity,
        Double meanMinutesToClose) {

    public FindingStats {
        closed = closed == null ? new Closed(0, 0, 0, 0, 0) : closed;
        bySeverity = bySeverity == null ? Map.of() : Map.copyOf(bySeverity);
    }

    /** The findings closed in the window, by how they closed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Closed(int total, int reverted, int accepted, int ignored, int vanished) {
    }
}
