package com.iaf.platform.releases.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.iaf.platform.releases.domain.Release.Gate;
import com.iaf.platform.releases.domain.Release.Jobs;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;

/**
 * The canary gates, judged over the finished jobs of the candidate and of the current version
 * since the stage started. Pure: no clock, no store, no Spring.
 *
 * <ul>
 * <li>{@code success-rate}: the candidate's success rate trails the current version's by at most one point;</li>
 * <li>{@code p95}: the candidate's p95 duration (successful jobs) is at most 20% slower;</li>
 * <li>{@code error-types}: no error class on the candidate that the current version never had;</li>
 * <li>{@code sample}: at least {@value #MIN_JOBS} candidate jobs and two hours in the stage; until then it is undecided.</li>
 * </ul>
 * Verdict: ADVANCE when every gate passed, ROLLBACK as soon as one gate is false, WAIT otherwise.
 * Cancelled jobs say nothing about a version and are left out. Without a baseline the comparison
 * gates stay undecided, except error-types, which judges the candidate alone.
 */
public final class ReleaseGates {

    public static final int MIN_JOBS = 20;
    public static final Duration MIN_TIME = Duration.ofHours(2);
    /** Percentage points the candidate's success rate may trail the current version's. */
    public static final double SUCCESS_RATE_TOLERANCE = 1.0;
    /** How much slower the candidate's p95 may be (0.2 = 20%). */
    public static final double P95_TOLERANCE = 0.20;

    private static final double EPSILON = 1e-9;

    public enum Verdict {
        ADVANCE, ROLLBACK, WAIT
    }

    public record Evaluation(List<Gate> gates, Verdict verdict, Jobs jobs) {
        /** The first gate that failed, in gate order. */
        public Optional<Gate> failing() {
            return gates.stream().filter(g -> Boolean.FALSE.equals(g.passed())).findFirst();
        }
    }

    private ReleaseGates() {
    }

    public static Evaluation evaluate(List<JobOutcome> candidate, List<JobOutcome> current, Instant stageStartedAt, Instant now) {
        Stats c = Stats.of(candidate);
        Stats b = Stats.of(current);
        List<Gate> gates = List.of(successRate(c, b), p95(c, b), errorTypes(c, b), sample(c, stageStartedAt, now));
        return new Evaluation(gates, verdict(gates), new Jobs(b.jobs(), c.jobs()));
    }

    /** The gates of a stage nothing has run in yet. */
    public static List<Gate> fresh(Instant now) {
        return evaluate(List.of(), List.of(), now, now).gates();
    }

    static Verdict verdict(List<Gate> gates) {
        if (gates.stream().anyMatch(g -> Boolean.FALSE.equals(g.passed()))) {
            return Verdict.ROLLBACK;
        }
        if (gates.stream().allMatch(g -> Boolean.TRUE.equals(g.passed()))) {
            return Verdict.ADVANCE;
        }
        return Verdict.WAIT;
    }

    static Gate successRate(Stats c, Stats b) {
        Double threshold = b.rate() == null ? null : round1(b.rate() - SUCCESS_RATE_TOLERANCE);
        if (c.rate() == null) {
            return new Gate("success-rate", "Success rate", null, null, threshold, "no finished jobs on the candidate yet");
        }
        double value = round1(c.rate());
        if (threshold == null) {
            return new Gate("success-rate", "Success rate", null, value, null,
                    pct(value) + " on the candidate; no finished jobs on the current version to compare with");
        }
        boolean passed = c.rate() + EPSILON >= b.rate() - SUCCESS_RATE_TOLERANCE;
        String detail = pct(value) + " on the candidate vs " + pct(round1(b.rate())) + " on the current version (may trail by "
                + (int) SUCCESS_RATE_TOLERANCE + " point)";
        return new Gate("success-rate", "Success rate", passed, value, threshold, detail);
    }

    static Gate p95(Stats c, Stats b) {
        Double threshold = b.p95() == null ? null : (double) Math.round(b.p95() * (1 + P95_TOLERANCE));
        if (c.p95() == null) {
            return new Gate("p95", "p95 duration", null, null, threshold, "no successful jobs on the candidate yet");
        }
        if (threshold == null) {
            return new Gate("p95", "p95 duration", null, c.p95(), null,
                    ms(c.p95()) + " on the candidate; no successful jobs on the current version to compare with");
        }
        boolean passed = c.p95() <= b.p95() * (1 + P95_TOLERANCE) + EPSILON;
        String detail = ms(c.p95()) + " on the candidate vs " + ms(b.p95()) + " on the current version (may be "
                + Math.round(P95_TOLERANCE * 100) + "% slower)";
        return new Gate("p95", "p95 duration", passed, c.p95(), threshold, detail);
    }

    static Gate errorTypes(Stats c, Stats b) {
        if (c.jobs() == 0) {
            return new Gate("error-types", "Error types", null, null, 0.0, "no finished jobs on the candidate yet");
        }
        Set<String> unseen = new TreeSet<>(c.errorClasses());
        unseen.removeAll(b.errorClasses());
        boolean passed = unseen.isEmpty();
        String detail;
        if (c.errorClasses().isEmpty()) {
            detail = "no failures on the candidate";
        } else if (passed) {
            detail = "every error class on the candidate (" + String.join(", ", c.errorClasses()) + ") also occurs on the current version";
        } else {
            detail = "new on the candidate: " + String.join(", ", unseen);
        }
        return new Gate("error-types", "Error types", passed, (double) unseen.size(), 0.0, detail);
    }

    static Gate sample(Stats c, Instant stageStartedAt, Instant now) {
        long millis = stageStartedAt == null || now == null ? 0 : Math.max(0, Duration.between(stageStartedAt, now).toMillis());
        double hours = Math.floor(millis / 360_000.0) / 10.0;
        boolean enough = c.jobs() >= MIN_JOBS && millis + 1 >= MIN_TIME.toMillis();
        String detail = enough
                ? c.jobs() + " jobs over " + hours1(hours) + "h in the stage"
                : c.jobs() + " of " + MIN_JOBS + " jobs, " + hours1(hours) + " of " + MIN_TIME.toHours() + "h";
        return new Gate("sample", "Sample size", enough ? Boolean.TRUE : null, (double) c.jobs(), (double) MIN_JOBS, detail);
    }

    /** What the gates need to know about one side. */
    record Stats(int jobs, int succeeded, Double rate, Double p95, Set<String> errorClasses) {

        static Stats of(List<JobOutcome> outcomes) {
            List<JobOutcome> relevant = outcomes == null ? List.of()
                    : outcomes.stream().filter(o -> o != null && counts(o.status())).toList();
            List<Long> durations = relevant.stream().filter(o -> "succeeded".equals(o.status())).map(Stats::duration)
                    .filter(d -> d >= 0).sorted().toList();
            Set<String> classes = new TreeSet<>();
            relevant.stream().filter(o -> !"succeeded".equals(o.status()))
                    .filter(o -> o.errorClass() != null && !o.errorClass().isBlank())
                    .forEach(o -> classes.add(o.errorClass()));
            int succeeded = (int) relevant.stream().filter(o -> "succeeded".equals(o.status())).count();
            Double rate = relevant.isEmpty() ? null : 100.0 * succeeded / relevant.size();
            Double p95 = durations.isEmpty() ? null
                    : (double) durations.get(Math.max(0, (int) Math.ceil(0.95 * durations.size()) - 1));
            return new Stats(relevant.size(), succeeded, rate, p95, classes);
        }

        /** Cancelled jobs are nobody's fault; only jobs that ran to an outcome count. */
        static boolean counts(String status) {
            return "succeeded".equals(status) || "failed".equals(status) || "rolled-back".equals(status);
        }

        static long duration(JobOutcome o) {
            if (o.durationMs() > 0) {
                return o.durationMs();
            }
            if (o.startedAt() != null && o.endedAt() != null) {
                return Duration.between(o.startedAt(), o.endedAt()).toMillis();
            }
            return -1;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    private static String hours1(double h) {
        return String.format(Locale.ROOT, "%.1f", h);
    }

    private static String ms(double v) {
        return v < 1000 ? Math.round(v) + "ms" : String.format(Locale.ROOT, "%.1fs", v / 1000.0);
    }
}
