package com.iaf.console.bff.insights.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RequestNumbersTest {

    // Friday 2026-10-23 12:00 UTC, and 30 days before it: Wednesday 2026-09-23 12:00 UTC
    private static final Instant TO = Instant.parse("2026-10-23T12:00:00Z");
    private static final Instant FROM = TO.minus(Duration.ofDays(30));

    private static RequestRecord succeeded(String id, String workflow, String person, Instant at, long ms, boolean selfServed) {
        return new RequestRecord("123456789012", id, workflow, person, RequestRecord.Outcome.SUCCEEDED, at, ms, selfServed);
    }

    private static RequestRecord failed(String id, String person, Instant at) {
        return new RequestRecord("123456789012", id, "create-vm", person, RequestRecord.Outcome.FAILED, at, 1_000L, false);
    }

    @Test
    void requestsAreCountedInTheWindowOnly() {
        List<RequestRecord> records = List.of(
                succeeded("j1", "create-vm", "dana", FROM.plusSeconds(60), 60_000, true),
                succeeded("j2", "create-vm", "dana", FROM.minusSeconds(1), 60_000, true),
                failed("j3", "mateo", TO.minusSeconds(60)),
                new RequestRecord("123456789012", "j4", "vm-restart", "mia", RequestRecord.Outcome.OTHER, TO, null, false),
                succeeded("j5", "vm-restart", "mateo", TO.minusSeconds(120), 30_000, false));
        RequestNumbers.Result r = RequestNumbers.of(records, FROM, TO, Baseline.NONE);
        assertEquals(new Insights.Requests(4, 1, 1), r.requests());
        assertEquals(60_000, r.leadTime().p50Ms());
        assertEquals(60_000, r.leadTime().p90Ms());
        assertNull(r.leadTime().baselineP50Ms());
        assertNull(r.leadTime().baselineSource());
        assertNull(r.hoursSaved(), "no baseline, no hours saved");
    }

    @Test
    void theLeadTimeIsTheNearestRankOverSelfServedRequests() {
        List<RequestRecord> records = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            records.add(succeeded("j" + i, "create-vm", "dana", FROM.plusSeconds(3600L * i), i * 1_000L, true));
        }
        records.add(succeeded("slow", "create-vm", "dana", FROM.plusSeconds(100), 999_000, false));
        RequestNumbers.Result r = RequestNumbers.of(records, FROM, TO, Baseline.NONE);
        assertEquals(5_000, r.leadTime().p50Ms());
        assertEquals(9_000, r.leadTime().p90Ms());
        assertEquals(10, r.requests().selfServed());
    }

    @Test
    void withoutSelfServedRequestsTheLeadTimeIsZero() {
        RequestNumbers.Result r = RequestNumbers.of(List.of(failed("j1", "dana", FROM.plusSeconds(10))), FROM, TO, Baseline.NONE);
        assertEquals(0, r.leadTime().p50Ms());
        assertEquals(0, r.leadTime().p90Ms());
    }

    @Test
    void hoursSavedAreTheBaselineLessTheLeadTimeNeverBelowZeroPerRequest() {
        Baseline baseline = new Baseline("ticket export, September 2026", Duration.ofHours(2), Map.of("vm-restart", Duration.ofMinutes(30)));
        List<RequestRecord> records = List.of(
                succeeded("j1", "create-vm", "dana", FROM.plusSeconds(60), Duration.ofMinutes(30).toMillis(), true),
                succeeded("j2", "vm-restart", "dana", FROM.plusSeconds(120), Duration.ofMinutes(45).toMillis(), true),
                succeeded("j3", "vm-restart", "mia", FROM.plusSeconds(180), Duration.ofMinutes(6).toMillis(), true));
        RequestNumbers.Result r = RequestNumbers.of(records, FROM, TO, baseline);
        // create-vm saves 1h30, the slow restart saves nothing (not -15 min), the quick one 24 min: 1.9 h
        assertEquals(1.9, r.hoursSaved());
        // the requests' own baselines are 2h, 30m and 30m: the median is 30 minutes
        assertEquals(Duration.ofMinutes(30).toMillis(), r.leadTime().baselineP50Ms());
        assertEquals("ticket export, September 2026", r.leadTime().baselineSource());
    }

    @Test
    void aBaselineWithNoSelfServedRequestsShowsTheOverallMedianAndNoHours() {
        Baseline baseline = new Baseline(null, Duration.ofDays(3), Map.of());
        RequestNumbers.Result r = RequestNumbers.of(List.of(), FROM, TO, baseline);
        assertEquals(Duration.ofDays(3).toMillis(), r.leadTime().baselineP50Ms());
        assertNull(r.leadTime().baselineSource());
        assertEquals(0.0, r.hoursSaved());
    }

    @Test
    void adoptionListsEveryWeekOfThePeriodFromItsMonday() {
        List<RequestRecord> records = List.of(
                succeeded("j1", "create-vm", "dana", Instant.parse("2026-09-23T13:00:00Z"), 1_000, true),
                succeeded("j2", "create-vm", "dana", Instant.parse("2026-09-24T13:00:00Z"), 1_000, false),
                failed("j3", "mateo", Instant.parse("2026-09-27T23:59:59Z")),
                succeeded("j4", "create-vm", "mia", Instant.parse("2026-10-19T00:00:00Z"), 1_000, true));
        List<Insights.Week> weeks = RequestNumbers.of(records, FROM, TO, Baseline.NONE).adoption();
        assertEquals(LocalDate.parse("2026-09-21"), weeks.get(0).weekStart());
        assertEquals(LocalDate.parse("2026-10-19"), weeks.get(weeks.size() - 1).weekStart());
        assertEquals(5, weeks.size());
        weeks.forEach(w -> assertEquals(DayOfWeek.MONDAY, w.weekStart().getDayOfWeek()));
        assertEquals(new Insights.Week(LocalDate.parse("2026-09-21"), 2, 3, 1), weeks.get(0));
        assertEquals(new Insights.Week(LocalDate.parse("2026-09-28"), 0, 0, 0), weeks.get(1));
        assertEquals(new Insights.Week(LocalDate.parse("2026-10-19"), 1, 1, 1), weeks.get(4));
    }

    @Test
    void aWeekStartsOnMondayInUtc() {
        assertEquals(LocalDate.parse("2026-10-19"), RequestNumbers.weekOf(Instant.parse("2026-10-25T23:59:59Z")));
        assertEquals(LocalDate.parse("2026-10-26"), RequestNumbers.weekOf(Instant.parse("2026-10-26T00:00:00Z")));
    }

    @Test
    void onlyASucceededRequestIsSelfServed() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestRecord("123456789012", "j1", "w", "p", RequestRecord.Outcome.FAILED, TO, 1L, true));
    }

    @Test
    void aBaselineIsPositive() {
        assertThrows(IllegalArgumentException.class, () -> new Baseline(null, Duration.ZERO, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Baseline(null, null, Map.of("w", Duration.ofMinutes(-1))));
        assertTrue(new Baseline(null, null, Map.of("w", Duration.ofMinutes(1))).configured());
    }
}
