package com.iaf.platform.releases.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.releases.domain.Release.Gate;
import com.iaf.platform.releases.domain.ReleaseGates.Evaluation;
import com.iaf.platform.releases.domain.ReleaseGates.Verdict;
import com.iaf.platform.runs.ports.inbound.RunsQueries.JobOutcome;

class ReleaseGatesTest {

    static final Instant STAGE_START = Instant.parse("2026-10-17T08:00:00Z");
    static final Instant THREE_HOURS_LATER = STAGE_START.plus(Duration.ofHours(3));

    static JobOutcome ok(String version, long durationMs) {
        return new JobOutcome(Ids.next("job"), "wf", version, "succeeded", STAGE_START, STAGE_START.plusMillis(durationMs), durationMs, null, null, null);
    }

    static JobOutcome failed(String version, String errorClass) {
        return new JobOutcome(Ids.next("job"), "wf", version, "failed", STAGE_START, STAGE_START.plusMillis(500), 500, errorClass, "step", null);
    }

    static JobOutcome cancelled(String version) {
        return new JobOutcome(Ids.next("job"), "wf", version, "cancelled", STAGE_START, STAGE_START.plusMillis(500), 500, null, null, null);
    }

    static List<JobOutcome> many(int n, Supplier<JobOutcome> outcome) {
        return Stream.generate(outcome).limit(n).toList();
    }

    @SafeVarargs
    static List<JobOutcome> all(List<JobOutcome>... lists) {
        List<JobOutcome> out = new ArrayList<>();
        for (List<JobOutcome> l : lists) {
            out.addAll(l);
        }
        return out;
    }

    static Gate gate(Evaluation e, String id) {
        return e.gates().stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void advancesWhenEveryGatePasses() {
        List<JobOutcome> candidate = many(25, () -> ok("1.2.0", 1000));
        List<JobOutcome> current = all(many(29, () -> ok("1.1.0", 1000)), List.of(failed("1.1.0", "Timeout")));

        Evaluation e = ReleaseGates.evaluate(candidate, current, STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.ADVANCE);
        assertThat(e.gates()).extracting(Gate::id).containsExactly("success-rate", "p95", "error-types", "sample");
        assertThat(e.gates()).allMatch(g -> Boolean.TRUE.equals(g.passed()));
        assertThat(e.jobs()).isEqualTo(new Release.Jobs(30, 25));
        assertThat(e.failing()).isEmpty();
    }

    @Test
    void waitsUntilTwentyCandidateJobsHaveFinished() {
        Evaluation e = ReleaseGates.evaluate(many(5, () -> ok("1.2.0", 1000)), many(30, () -> ok("1.1.0", 1000)), STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.WAIT);
        Gate sample = gate(e, "sample");
        assertThat(sample.passed()).isNull();
        assertThat(sample.value()).isEqualTo(5.0);
        assertThat(sample.threshold()).isEqualTo(20.0);
        assertThat(sample.detail()).isEqualTo("5 of 20 jobs, 3.0 of 2h");
    }

    @Test
    void waitsUntilTwoHoursHavePassedInTheStage() {
        Evaluation e = ReleaseGates.evaluate(many(25, () -> ok("1.2.0", 1000)), many(30, () -> ok("1.1.0", 1000)),
                STAGE_START, STAGE_START.plus(Duration.ofMinutes(90)));

        assertThat(e.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(gate(e, "sample").passed()).isNull();
        assertThat(gate(e, "sample").detail()).isEqualTo("25 of 20 jobs, 1.5 of 2h");
    }

    @Test
    void sampleGateReportsTheSampleOnceItIsLargeEnough() {
        Evaluation e = ReleaseGates.evaluate(many(34, () -> ok("1.2.0", 1000)), many(30, () -> ok("1.1.0", 1000)), STAGE_START, THREE_HOURS_LATER);

        assertThat(gate(e, "sample").passed()).isTrue();
        assertThat(gate(e, "sample").detail()).isEqualTo("34 jobs over 3.0h in the stage");
    }

    @Test
    void rollsBackOnAnErrorClassTheCurrentVersionNeverHad() {
        List<JobOutcome> candidate = all(many(49, () -> ok("1.2.0", 1000)), List.of(failed("1.2.0", "ReplicaSetNotReady")));
        List<JobOutcome> current = all(many(40, () -> ok("1.1.0", 1000)), List.of(failed("1.1.0", "Timeout")));

        Evaluation e = ReleaseGates.evaluate(candidate, current, STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.ROLLBACK);
        assertThat(gate(e, "success-rate").passed()).isTrue();
        Gate errors = gate(e, "error-types");
        assertThat(errors.passed()).isFalse();
        assertThat(errors.value()).isEqualTo(1.0);
        assertThat(errors.threshold()).isEqualTo(0.0);
        assertThat(errors.detail()).isEqualTo("new on the candidate: ReplicaSetNotReady");
        assertThat(e.failing()).map(Gate::id).contains("error-types");
    }

    @Test
    void errorClassesTheCurrentVersionAlsoHasAreFine() {
        List<JobOutcome> candidate = all(many(49, () -> ok("1.2.0", 1000)), List.of(failed("1.2.0", "Timeout")));
        List<JobOutcome> current = all(many(40, () -> ok("1.1.0", 1000)), List.of(failed("1.1.0", "Timeout")));

        Gate errors = gate(ReleaseGates.evaluate(candidate, current, STAGE_START, THREE_HOURS_LATER), "error-types");

        assertThat(errors.passed()).isTrue();
        assertThat(errors.detail()).isEqualTo("every error class on the candidate (Timeout) also occurs on the current version");
    }

    @Test
    void rollsBackWhenTheSuccessRateTrailsByMoreThanOnePoint() {
        List<JobOutcome> candidate = all(many(97, () -> ok("1.2.0", 1000)), many(3, () -> failed("1.2.0", "Timeout")));
        List<JobOutcome> current = all(many(99, () -> ok("1.1.0", 1000)), List.of(failed("1.1.0", "Timeout")));

        Evaluation e = ReleaseGates.evaluate(candidate, current, STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.ROLLBACK);
        Gate rate = gate(e, "success-rate");
        assertThat(rate.passed()).isFalse();
        assertThat(rate.value()).isEqualTo(97.0);
        assertThat(rate.threshold()).isEqualTo(98.0);
        assertThat(rate.detail()).isEqualTo("97.0% on the candidate vs 99.0% on the current version (may trail by 1 point)");
        assertThat(e.failing()).map(Gate::id).contains("success-rate");
    }

    @Test
    void aSuccessRateExactlyOnePointLowerStillPasses() {
        List<JobOutcome> candidate = all(many(99, () -> ok("1.2.0", 1000)), List.of(failed("1.2.0", "Timeout")));
        List<JobOutcome> current = all(many(99, () -> ok("1.1.0", 1000)), List.of(failed("1.1.0", "Timeout")));
        List<JobOutcome> perfect = many(100, () -> ok("1.1.0", 1000));

        Gate same = gate(ReleaseGates.evaluate(candidate, current, STAGE_START, THREE_HOURS_LATER), "success-rate");
        Gate boundary = gate(ReleaseGates.evaluate(candidate, perfect, STAGE_START, THREE_HOURS_LATER), "success-rate");

        assertThat(same.passed()).isTrue();
        assertThat(boundary.passed()).as("99% against a 100% baseline is exactly one point: allowed").isTrue();
        assertThat(boundary.value()).isEqualTo(99.0);
        assertThat(boundary.threshold()).isEqualTo(99.0);
    }

    @Test
    void rollsBackWhenP95IsMoreThanTwentyPercentSlower() {
        List<JobOutcome> slow = many(25, () -> ok("1.2.0", 1300));
        List<JobOutcome> atTheLimit = many(25, () -> ok("1.2.0", 1200));
        List<JobOutcome> current = many(30, () -> ok("1.1.0", 1000));

        Evaluation tooSlow = ReleaseGates.evaluate(slow, current, STAGE_START, THREE_HOURS_LATER);
        Evaluation limit = ReleaseGates.evaluate(atTheLimit, current, STAGE_START, THREE_HOURS_LATER);

        assertThat(tooSlow.verdict()).isEqualTo(Verdict.ROLLBACK);
        assertThat(gate(tooSlow, "p95").passed()).isFalse();
        assertThat(gate(tooSlow, "p95").value()).isEqualTo(1300.0);
        assertThat(gate(tooSlow, "p95").threshold()).isEqualTo(1200.0);
        assertThat(gate(tooSlow, "p95").detail()).isEqualTo("1.3s on the candidate vs 1.0s on the current version (may be 20% slower)");
        assertThat(gate(limit, "p95").passed()).isTrue();
        assertThat(limit.verdict()).isEqualTo(Verdict.ADVANCE);
    }

    @Test
    void p95IsTheNinetyFifthPercentileOfSuccessfulJobs() {
        List<JobOutcome> candidate = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            candidate.add(ok("1.2.0", i * 100L));
        }
        candidate.add(failed("1.2.0", "Timeout"));

        Gate p95 = gate(ReleaseGates.evaluate(candidate, many(20, () -> ok("1.1.0", 1000)), STAGE_START, THREE_HOURS_LATER), "p95");

        assertThat(p95.value()).isEqualTo(1900.0);
    }

    @Test
    void comparisonGatesStayUndecidedWithoutABaseline() {
        Evaluation e = ReleaseGates.evaluate(many(25, () -> ok("1.2.0", 1000)), List.of(), STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.WAIT);
        assertThat(gate(e, "success-rate").passed()).isNull();
        assertThat(gate(e, "success-rate").value()).isEqualTo(100.0);
        assertThat(gate(e, "success-rate").threshold()).isNull();
        assertThat(gate(e, "p95").passed()).isNull();
        assertThat(gate(e, "error-types").passed()).isTrue();
        assertThat(gate(e, "sample").passed()).isTrue();
    }

    @Test
    void aFailingCandidateWithoutABaselineRollsBack() {
        List<JobOutcome> candidate = all(many(24, () -> ok("1.2.0", 1000)), List.of(failed("1.2.0", "Boom")));

        Evaluation e = ReleaseGates.evaluate(candidate, List.of(), STAGE_START, THREE_HOURS_LATER);

        assertThat(e.verdict()).isEqualTo(Verdict.ROLLBACK);
        assertThat(e.failing()).map(Gate::id).contains("error-types");
    }

    @Test
    void cancelledJobsCountForNothing() {
        List<JobOutcome> candidate = all(many(10, () -> ok("1.2.0", 1000)), many(15, () -> cancelled("1.2.0")));

        Evaluation e = ReleaseGates.evaluate(candidate, many(30, () -> ok("1.1.0", 1000)), STAGE_START, THREE_HOURS_LATER);

        assertThat(e.jobs().candidate()).isEqualTo(10);
        assertThat(gate(e, "sample").detail()).isEqualTo("10 of 20 jobs, 3.0 of 2h");
        assertThat(gate(e, "success-rate").value()).isEqualTo(100.0);
    }

    @Test
    void freshGatesOfANewStageAreAllUndecided() {
        List<Gate> fresh = ReleaseGates.fresh(STAGE_START);

        assertThat(fresh).hasSize(4);
        assertThat(fresh).allMatch(g -> g.passed() == null);
        assertThat(fresh.get(3).detail()).isEqualTo("0 of 20 jobs, 0.0 of 2h");
        assertThat(ReleaseGates.verdict(fresh)).isEqualTo(Verdict.WAIT);
    }
}
