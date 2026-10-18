package com.iaf.platform.providers.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.domain.Health.Status;
import com.iaf.platform.providers.domain.ProviderDefinition.Slo;

/**
 * The health bands, judged in one place so the UI only renders them. Over the samples of the last
 * 24 hours: unknown with fewer than {@value #MIN_CALLS} calls; failing when the success rate is
 * under the {@value #FAILING_RATE}% floor or the p95 is over three times the SLO; degraded when the
 * success rate is under the SLO (default 99%) or the p95 is over the SLO; healthy otherwise. The
 * error trend beside the band is longer: the failed calls of each of the last {@value #TREND_DAYS}
 * days, as 24-hour windows ending now, oldest first.
 */
public final class ProviderHealthRule {

    public static final int MIN_CALLS = 5;
    public static final double FAILING_RATE = 95.0;
    public static final int FAILING_P95_FACTOR = 3;
    public static final Duration WINDOW = Duration.ofHours(24);
    public static final int TREND_DAYS = 14;

    private ProviderHealthRule() {
    }

    /**
     * Judge a provider from its samples: the band and the metrics over the last 24 hours, the error
     * trend over the last {@value #TREND_DAYS} days (older samples are ignored, so the caller may
     * hand over either span). {@code slo} null means the default SLO.
     */
    public static Health judge(List<CallSample> samples, Slo slo, Instant now) {
        Slo target = slo == null ? Slo.DEFAULT : slo;
        List<CallSample> all = samples == null ? List.of() : samples;
        Instant dayAgo = now.minus(WINDOW);
        List<CallSample> window = all.stream().filter(s -> s.at() != null && !s.at().isBefore(dayAgo)).toList();
        int calls = window.size();
        List<Integer> trend = errorTrend(all, now);
        if (calls < MIN_CALLS) {
            String reason = calls == 0
                    ? "No calls in the last 24 hours"
                    : "Only " + calls + (calls == 1 ? " call" : " calls") + " in the last 24 hours; at least " + MIN_CALLS + " are needed to judge health";
            return new Health(Status.UNKNOWN, calls == 0 ? 0.0 : successRate(window), percentile(window, 50), percentile(window, 95), calls, trend, List.of(reason));
        }
        double rate = successRate(window);
        long p50 = percentile(window, 50);
        long p95 = percentile(window, 95);
        List<String> reasons = new ArrayList<>();
        Status status = Status.HEALTHY;
        if (rate < FAILING_RATE) {
            status = Status.FAILING;
            reasons.add("Success rate " + pct(rate) + " is below the " + pct(FAILING_RATE) + " floor");
        } else if (rate < target.successRate()) {
            status = Status.DEGRADED;
            reasons.add("Success rate " + pct(rate) + " is below the " + pct(target.successRate()) + " SLO");
        }
        if (p95 > (long) FAILING_P95_FACTOR * target.p95Ms()) {
            status = Status.FAILING;
            reasons.add("p95 latency " + p95 + " ms is above three times the " + target.p95Ms() + " ms SLO");
        } else if (p95 > target.p95Ms()) {
            if (status != Status.FAILING) {
                status = Status.DEGRADED;
            }
            reasons.add("p95 latency " + p95 + " ms is above the " + target.p95Ms() + " ms SLO");
        }
        return new Health(status, rate, p50, p95, calls, trend, reasons);
    }

    /** Success rate in percent, one decimal. */
    public static double successRate(List<CallSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        long ok = samples.stream().filter(CallSample::ok).count();
        return Math.round(ok * 1000.0 / samples.size()) / 10.0;
    }

    /** Nearest-rank percentile of the durations, 0 when there are no samples. */
    public static long percentile(List<CallSample> samples, int p) {
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(samples.stream().map(CallSample::durationMs).toList());
        Collections.sort(sorted);
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** Failed calls per day over the last {@value #TREND_DAYS} days, oldest day first. */
    public static List<Integer> errorTrend(List<CallSample> samples, Instant now) {
        List<Integer> out = new ArrayList<>(TREND_DAYS);
        for (List<CallSample> window : dailyWindows(samples, now)) {
            out.add((int) window.stream().filter(s -> !s.ok()).count());
        }
        return out;
    }

    /**
     * The last {@value #TREND_DAYS} days as 24-hour windows ending now, oldest first, windows without
     * calls included (empty). The newest window is a whole day like the others, so it compares with
     * the one before it (a calendar day just past midnight would read as an outage), and it holds
     * the same calls the band is judged on. Samples older than the span or after now are left out.
     */
    public static List<List<CallSample>> dailyWindows(List<CallSample> samples, Instant now) {
        Instant from = now.minus(Duration.ofDays(TREND_DAYS));
        List<List<CallSample>> windows = new ArrayList<>(TREND_DAYS);
        for (int i = 0; i < TREND_DAYS; i++) {
            windows.add(new ArrayList<>());
        }
        if (samples == null) {
            return windows;
        }
        for (CallSample s : samples) {
            if (s.at() == null || s.at().isBefore(from) || s.at().isAfter(now)) {
                continue;
            }
            windows.get((int) Math.min(TREND_DAYS - 1, Duration.between(from, s.at()).toDays())).add(s);
        }
        return windows;
    }

    static String pct(double value) {
        return (value == Math.floor(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value)) + "%";
    }
}
