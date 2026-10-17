package com.iaf.platform.providers.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.domain.Health.Status;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;

class ProviderHealthRuleTest {

    static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    static final Slo SLO = new Slo(99.5, 800);

    static CallSample sample(Instant at, boolean ok, long ms, String errorClass) {
        return new CallSample(at, "fleet", "vm.get", "dev", ok, ms, errorClass, ok ? null : "job_1", null, null);
    }

    static List<CallSample> samples(int ok, long okMs, int failed, long failedMs) {
        List<CallSample> out = new ArrayList<>();
        for (int i = 0; i < ok; i++) {
            out.add(sample(NOW.minusSeconds(60L * (i + 1)), true, okMs, null));
        }
        for (int i = 0; i < failed; i++) {
            out.add(sample(NOW.minusSeconds(30L * (i + 1)), false, failedMs, "unavailable"));
        }
        return out;
    }

    @Test
    void fewerThanFiveCallsIsUnknown() {
        Health h = ProviderHealthRule.judge(samples(4, 100, 0, 0), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.UNKNOWN);
        assertThat(h.calls()).isEqualTo(4);
        assertThat(h.reasons()).containsExactly("Only 4 calls in the last 24 hours; at least 5 are needed to judge health");
        assertThat(ProviderHealthRule.judge(List.of(), SLO, NOW).reasons()).containsExactly("No calls in the last 24 hours");
        assertThat(ProviderHealthRule.judge(null, null, NOW).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void healthyWhenTheSloHolds() {
        Health h = ProviderHealthRule.judge(samples(100, 100, 0, 0), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.HEALTHY);
        assertThat(h.successRate()).isEqualTo(100.0);
        assertThat(h.p50Ms()).isEqualTo(100);
        assertThat(h.p95Ms()).isEqualTo(100);
        assertThat(h.reasons()).isEmpty();
        assertThat(h.errorTrend()).hasSize(24).containsOnly(0);
    }

    @Test
    void degradedWhenTheSuccessRateIsUnderTheSlo() {
        Health h = ProviderHealthRule.judge(samples(98, 100, 2, 100), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.DEGRADED);
        assertThat(h.successRate()).isEqualTo(98.0);
        assertThat(h.reasons()).containsExactly("Success rate 98% is below the 99.5% SLO");
    }

    @Test
    void failingWhenTheSuccessRateIsUnderTheFloor() {
        Health h = ProviderHealthRule.judge(samples(9, 100, 1, 100), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.FAILING);
        assertThat(h.successRate()).isEqualTo(90.0);
        assertThat(h.reasons()).containsExactly("Success rate 90% is below the 95% floor");
    }

    @Test
    void degradedWhenTheP95IsOverTheSlo() {
        Health h = ProviderHealthRule.judge(samples(10, 100, 0, 0).subList(0, 10), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.HEALTHY);
        List<CallSample> slow = new ArrayList<>(samples(10, 100, 0, 0));
        for (int i = 0; i < 10; i++) {
            slow.add(sample(NOW.minusSeconds(5L * (i + 1)), true, 900, null));
        }
        Health d = ProviderHealthRule.judge(slow, SLO, NOW);
        assertThat(d.status()).isEqualTo(Status.DEGRADED);
        assertThat(d.p95Ms()).isEqualTo(900);
        assertThat(d.reasons()).containsExactly("p95 latency 900 ms is above the 800 ms SLO");
    }

    @Test
    void failingWhenTheP95IsOverThreeTimesTheSlo() {
        Health h = ProviderHealthRule.judge(samples(10, 3000, 0, 0), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.FAILING);
        assertThat(h.reasons()).containsExactly("p95 latency 3000 ms is above three times the 800 ms SLO");
    }

    @Test
    void bothReasonsAreListedAndTheWorstBandWins() {
        Health h = ProviderHealthRule.judge(samples(98, 900, 2, 900), SLO, NOW);
        assertThat(h.status()).isEqualTo(Status.DEGRADED);
        assertThat(h.reasons()).containsExactly("Success rate 98% is below the 99.5% SLO", "p95 latency 900 ms is above the 800 ms SLO");
        Health f = ProviderHealthRule.judge(samples(98, 2500, 2, 2500), SLO, NOW);
        assertThat(f.status()).isEqualTo(Status.FAILING);
        assertThat(f.reasons()).containsExactly("Success rate 98% is below the 99.5% SLO", "p95 latency 2500 ms is above three times the 800 ms SLO");
    }

    @Test
    void theDefaultSloIsNinetyNinePercentAndOneSecond() {
        Health h = ProviderHealthRule.judge(samples(99, 100, 1, 100), null, NOW);
        assertThat(h.status()).isEqualTo(Status.HEALTHY);
        Health d = ProviderHealthRule.judge(samples(98, 100, 2, 100), null, NOW);
        assertThat(d.reasons()).containsExactly("Success rate 98% is below the 99% SLO");
        Health slow = ProviderHealthRule.judge(samples(10, 1001, 0, 0), null, NOW);
        assertThat(slow.status()).isEqualTo(Status.DEGRADED);
    }

    @Test
    void errorTrendCountsFailedCallsPerHourOldestFirst() {
        List<CallSample> samples = List.of(
                sample(NOW.minus(Duration.ofMinutes(30)), false, 100, "unavailable"),
                sample(NOW.minus(Duration.ofMinutes(31)), false, 100, "unavailable"),
                sample(NOW.minus(Duration.ofHours(23)).minus(Duration.ofMinutes(30)), false, 100, "timeout"),
                sample(NOW.minus(Duration.ofHours(2)), true, 100, null),
                sample(NOW.minus(Duration.ofHours(25)), false, 100, "old"));
        List<Integer> trend = ProviderHealthRule.errorTrend(samples, NOW);
        assertThat(trend).hasSize(24);
        assertThat(trend.get(23)).isEqualTo(2);
        assertThat(trend.get(0)).isEqualTo(1);
        assertThat(trend.stream().mapToInt(Integer::intValue).sum()).isEqualTo(3);
    }

    @Test
    void percentilesUseTheNearestRank() {
        List<CallSample> s = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            s.add(sample(NOW.minusSeconds(i), true, i, null));
        }
        assertThat(ProviderHealthRule.percentile(s, 50)).isEqualTo(50);
        assertThat(ProviderHealthRule.percentile(s, 95)).isEqualTo(95);
        assertThat(ProviderHealthRule.percentile(List.of(), 95)).isZero();
        assertThat(ProviderHealthRule.successRate(samples(2, 1, 1, 1))).isEqualTo(66.7);
    }
}
