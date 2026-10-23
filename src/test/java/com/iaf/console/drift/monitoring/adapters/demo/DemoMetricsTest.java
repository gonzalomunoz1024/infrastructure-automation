package com.iaf.console.drift.monitoring.adapters.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.rightsizing.domain.Utilisation;
import com.iaf.console.kit.scope.AccountScope;

/** The demo's made-up series: the same numbers on every read, points on the step, and levels the resource's name picks. */
class DemoMetricsTest {

    static final AccountScope SCOPE = AccountScope.of("123456789012", "global", "dana");
    static final TypeInfo VM = new TypeInfo("vm", "vms", "compute", "vsphere", "vm.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing", false,
            false, Set.of(), List.of());
    static final TypeInfo DISK = new TypeInfo("disk", "disks", "storage", "vsphere", "disk.get", Map.of(), Set.of(), Duration.ofMinutes(15), "missing",
            false, false, Set.of(), List.of());

    private final DemoMetrics demo = new DemoMetrics(Set.of("vm"), Set.of("batch-01"), Set.of("web-02"), Set.of("cache-01"));

    private ResourceMetrics read(String name, Instant from, Instant to, Duration step) {
        MetricsTarget target = new MetricsTarget("wfrn:wf:compute:global:123456789012:vm/" + name, "123456789012", "global", "vm", name, name, null,
                false);
        return demo.getMetrics(SCOPE, target, VM, new MetricsWindow(from, to, step)).block();
    }

    private static List<Double> values(ResourceMetrics m, String series) {
        return m.series(series).map(MetricSeries::values).orElseThrow();
    }

    @Test
    void theSameReadAnswersTheSameNumbersOnTheStep() {
        Instant from = Instant.parse("2026-10-20T00:02:00Z");
        Instant to = Instant.parse("2026-10-20T01:00:00Z");
        ResourceMetrics first = read("web-01", from, to, Duration.ofMinutes(5));
        assertThat(read("web-01", from, to, Duration.ofMinutes(5))).isEqualTo(first);
        assertThat(first.series()).extracting(MetricSeries::name).containsExactly("cpuPercent", "memoryPercent", "diskPercent");
        MetricSeries cpu = first.series().get(0);
        assertThat(cpu.unit()).isEqualTo("percent");
        assertThat(cpu.points()).hasSize(12);
        assertThat(cpu.points().get(0).at()).isEqualTo(Instant.parse("2026-10-20T00:05:00Z"));
        assertThat(cpu.points().get(11).at()).isEqualTo(to);
        assertThat(first.provider()).isEqualTo("demo");
        assertThat(demo.supports(VM)).isTrue();
        assertThat(demo.supports(DISK)).isFalse();
        assertThat(demo.seriesOf(DISK)).contains("cpuPercent");
    }

    @Test
    void theNamePicksTheLevel() {
        Instant to = Instant.parse("2026-10-23T00:00:00Z");
        Instant from = to.minus(Duration.ofDays(14));
        Utilisation idle = utilisation(read("batch-01", from, to, Duration.ofHours(1)));
        Utilisation oversized = utilisation(read("web-02", from, to, Duration.ofHours(1)));
        Utilisation busy = utilisation(read("cache-01", from, to, Duration.ofHours(1)));
        Utilisation ordinary = utilisation(read("web-01", from, to, Duration.ofHours(1)));
        assertThat(idle.cpuP95()).isLessThan(5);
        assertThat(idle.memoryP95()).isLessThan(10);
        assertThat(oversized.cpuP95()).isBetween(5.0, 20.0);
        assertThat(oversized.memoryP95()).isBetween(10.0, 30.0);
        assertThat(busy.cpuP95()).isGreaterThan(70);
        assertThat(ordinary.cpuP95()).isBetween(30.0, 70.0);
        assertThat(ordinary.memoryP95()).isGreaterThan(30);
        assertThat(ordinary.samples()).isEqualTo(337);
        for (Double v : values(read("cache-01", from, to, Duration.ofHours(1)), "cpuPercent")) {
            assertThat(v).isBetween(0.0, 100.0);
        }
    }

    private static Utilisation utilisation(ResourceMetrics m) {
        return Utilisation.of(m.from(), m.to(), values(m, "cpuPercent"), values(m, "memoryPercent"));
    }
}
