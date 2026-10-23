package com.iaf.console.bff.insights.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The request numbers of a period from its requests: how many started, how many were
 * self-served and how many failed; the lead time of the self-served ones (nearest-rank p50 and
 * p90) against the ticket baseline; the hours that saved; and adoption week by week, each week
 * starting on a Monday (UTC), every week of the period listed, oldest first.
 */
public final class RequestNumbers {

    private static final double MS_PER_HOUR = 3_600_000d;

    /** What the requests add up to. */
    public record Result(Insights.Requests requests, Insights.LeadTime leadTime, Double hoursSaved, List<Insights.Week> adoption) {
    }

    private static final class Tally {
        long requests;
        long selfServed;
        final Set<String> people = new HashSet<>();
    }

    private RequestNumbers() {
    }

    /** The numbers of the requests started in {@code [from, to]}; others are left out. */
    public static Result of(List<RequestRecord> records, Instant from, Instant to, Baseline baseline) {
        Map<LocalDate, Tally> weeks = new TreeMap<>();
        for (LocalDate week = weekOf(from); !week.isAfter(weekOf(to)); week = week.plusWeeks(1)) {
            weeks.put(week, new Tally());
        }
        long total = 0;
        long selfServed = 0;
        long failed = 0;
        List<Long> leadTimes = new ArrayList<>();
        List<Long> baselines = new ArrayList<>();
        long savedMs = 0;
        for (RequestRecord r : records) {
            if (r.startedAt().isBefore(from) || r.startedAt().isAfter(to)) {
                continue;
            }
            total++;
            if (r.outcome() == RequestRecord.Outcome.FAILED) {
                failed++;
            }
            Tally week = weeks.get(weekOf(r.startedAt()));
            week.requests++;
            if (r.person() != null) {
                week.people.add(r.person());
            }
            if (!r.selfServed()) {
                continue;
            }
            selfServed++;
            week.selfServed++;
            long lead = r.durationMs() == null ? 0 : Math.max(0, r.durationMs());
            leadTimes.add(lead);
            Optional<Duration> ticket = baseline.of(r.workflow());
            if (ticket.isPresent()) {
                long ticketMs = ticket.get().toMillis();
                baselines.add(ticketMs);
                savedMs += Math.max(0, ticketMs - lead);
            }
        }
        Collections.sort(leadTimes);
        Collections.sort(baselines);
        Long baselineP50 = !baselines.isEmpty() ? Long.valueOf(nearestRank(baselines, 0.5))
                : baseline.leadTime() == null ? null : Long.valueOf(baseline.leadTime().toMillis());
        Insights.LeadTime leadTime = new Insights.LeadTime(nearestRank(leadTimes, 0.5), nearestRank(leadTimes, 0.9), baselineP50,
                baseline.configured() ? baseline.source() : null);
        Double hoursSaved = baseline.configured() ? Math.round(savedMs / MS_PER_HOUR * 10d) / 10d : null;
        List<Insights.Week> adoption = new ArrayList<>(weeks.size());
        weeks.forEach((start, t) -> adoption.add(new Insights.Week(start, t.people.size(), t.requests, t.selfServed)));
        return new Result(new Insights.Requests(total, selfServed, failed), leadTime, hoursSaved, adoption);
    }

    /** The Monday (UTC) of the week an instant falls in. */
    public static LocalDate weekOf(Instant at) {
        return at.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** The nearest-rank percentile of sorted values; 0 when there are none. */
    static long nearestRank(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size(), rank) - 1));
    }
}
