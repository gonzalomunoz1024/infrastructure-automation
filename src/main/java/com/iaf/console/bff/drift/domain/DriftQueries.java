package com.iaf.console.bff.drift.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The questions the drift pages ask the drift service, already checked by the BFF (enums, times,
 * page size), so the port only spells them as query parameters. A null leaves a filter out; the
 * account and the region always come from the session's scope, never from here.
 */
public final class DriftQueries {

    private DriftQueries() {
    }

    /** A page of findings: {@code status} open, closed or all; {@code wfrn} exact or a pattern. */
    public record Findings(String status, String severity, String type, String wfrn, Instant openedAfter, int limit, String after) {
    }

    /** A page of unmanaged objects: {@code owner} any, mine or unknown. */
    public record Unmanaged(String type, String owner, int limit, String after) {
    }

    /** A page of alarms, of one resource when {@code wfrn} is set. */
    public record Alarms(String wfrn, String state, int limit, String after) {
    }

    /** A page of recommendations. */
    public record Recommendations(String type, String kind, String status, String wfrn, int limit, String after) {
    }

    /** One resource's series over a range; a null {@code from}, {@code to} or {@code step} leaves the drift service's default. */
    public record Metrics(String wfrn, Instant from, Instant to, Duration step) {
    }
}
