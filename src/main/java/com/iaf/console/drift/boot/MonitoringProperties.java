package com.iaf.console.drift.boot;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The settings of monitoring and rightsizing under {@code console.monitoring.*}, kept apart from
 * {@link DriftProperties}, which the drift detection owns. Every one has a default; the series come
 * from the platform's metrics read unless {@code metrics.mode} is {@code demo}, which the demo
 * profile sets, so production never shows made-up numbers by accident.
 */
@ConfigurationProperties(prefix = "console.monitoring")
public record MonitoringProperties(MetricsSettings metrics, AlarmSettings alarms, RightsizingSettings rightsizing) {

    public MonitoringProperties {
        metrics = metrics == null ? new MetricsSettings(null, null, null, null) : metrics;
        alarms = alarms == null ? new AlarmSettings(null, null, null) : alarms;
        rightsizing = rightsizing == null
                ? new RightsizingSettings(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                : rightsizing;
    }

    /**
     * Where the series come from ({@code platform}: the type's metrics read with the account's
     * credential; {@code demo}: made-up series), and how far one read of {@code GET /v1/metrics} may
     * reach: the longest range and the most steps it may hold.
     */
    public record MetricsSettings(String mode, Duration maxRange, Integer maxSteps, DemoSettings demo) {
        public MetricsSettings {
            mode = mode == null || mode.isBlank() ? "platform" : mode.trim();
            maxRange = maxRange == null || maxRange.isNegative() || maxRange.isZero() ? Duration.ofDays(92) : maxRange;
            maxSteps = maxSteps == null || maxSteps < 1 ? 5000 : maxSteps;
            demo = demo == null ? new DemoSettings(null, null, null, null) : demo;
        }

        public boolean demoMode() {
            return "demo".equalsIgnoreCase(mode);
        }
    }

    /** The demo's made-up series: the types that have them, and the resources (by name) that are idle, oversized or busy. */
    public record DemoSettings(List<String> types, List<String> idle, List<String> oversized, List<String> busy) {
        public DemoSettings {
            types = types == null ? List.of("vm") : List.copyOf(types);
            idle = idle == null ? List.of("batch-01", "shop-01") : List.copyOf(idle);
            oversized = oversized == null ? List.of("web-02") : List.copyOf(oversized);
            busy = busy == null ? List.of("cache-01") : List.copyOf(busy);
        }
    }

    /** The evaluation of every alarm: on or off, how often (the contract says every minute), how many resources are read at once. */
    public record AlarmSettings(Boolean enabled, Duration interval, Integer concurrency) {
        public AlarmSettings {
            enabled = enabled == null || enabled;
            interval = interval == null || interval.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofMinutes(1) : interval;
            concurrency = concurrency == null || concurrency < 1 ? 4 : concurrency;
        }
    }

    /**
     * The utilisation pass and its rules. Each resource is read once an {@code interval} (daily); the
     * loop wakes every {@code check-interval} and reads the resources whose turn has come, so a pass
     * that is cut short goes on at the next wake. It reads {@code window} (14 days) at {@code step} (an
     * hour) and judges only with {@code min-history} of points (a week). The thresholds are percentages
     * of the 95th percentile: under both idle ones is idle, under an oversized one shrinks that
     * dimension to the smallest allowed value that keeps it under {@code target-p95}. The series and
     * size fields name CPU and memory; a size without allowed values in the update workflow or the
     * type is halved down to {@code min-cpu} and {@code min-memory}.
     */
    public record RightsizingSettings(Boolean enabled, Duration interval, Duration checkInterval, Duration initialDelay, Duration window, Duration step,
            Duration minHistory, Double idleCpu, Double idleMemory, Double oversizedCpu, Double oversizedMemory, Double targetP95, String cpuSeries,
            String memorySeries, String cpuField, String memoryField, Double minCpu, Double minMemory, Integer concurrency) {
        public RightsizingSettings {
            enabled = enabled == null || enabled;
            interval = positive(interval, Duration.ofHours(24));
            checkInterval = positive(checkInterval, Duration.ofHours(1));
            initialDelay = initialDelay == null || initialDelay.isNegative() ? Duration.ofMinutes(1) : initialDelay;
            window = positive(window, Duration.ofDays(14));
            step = step == null || step.compareTo(Duration.ofMinutes(1)) < 0 ? Duration.ofHours(1) : step;
            minHistory = minHistory == null || minHistory.isNegative() ? Duration.ofDays(7) : minHistory;
            idleCpu = idleCpu == null ? 5.0 : idleCpu;
            idleMemory = idleMemory == null ? 10.0 : idleMemory;
            oversizedCpu = oversizedCpu == null ? 20.0 : oversizedCpu;
            oversizedMemory = oversizedMemory == null ? 30.0 : oversizedMemory;
            targetP95 = targetP95 == null ? 60.0 : targetP95;
            cpuSeries = blank(cpuSeries) ? "cpuPercent" : cpuSeries;
            memorySeries = blank(memorySeries) ? "memoryPercent" : memorySeries;
            cpuField = blank(cpuField) ? "cpu" : cpuField;
            memoryField = blank(memoryField) ? "memoryGb" : memoryField;
            minCpu = minCpu == null || minCpu <= 0 ? 1.0 : minCpu;
            minMemory = minMemory == null || minMemory <= 0 ? 2.0 : minMemory;
            concurrency = concurrency == null || concurrency < 1 ? 4 : concurrency;
        }

        /** The points a judgement needs: the minimum history at the read's step. */
        public int minSamples() {
            return (int) Math.max(1, minHistory.toMillis() / step.toMillis());
        }

        private static Duration positive(Duration value, Duration otherwise) {
            return value == null || value.isNegative() || value.isZero() ? otherwise : value;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
