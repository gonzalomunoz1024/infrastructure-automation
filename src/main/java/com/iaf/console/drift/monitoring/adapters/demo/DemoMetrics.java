package com.iaf.console.drift.monitoring.adapters.demo;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.drift.monitoring.domain.MetricPoint;
import com.iaf.console.drift.monitoring.domain.MetricSeries;
import com.iaf.console.drift.monitoring.domain.MetricsTarget;
import com.iaf.console.drift.monitoring.domain.MetricsWindow;
import com.iaf.console.drift.monitoring.domain.ResourceMetrics;
import com.iaf.console.drift.monitoring.ports.outbound.MetricsPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The demo's metric series ({@code console.monitoring.metrics.mode: demo}). The fake platform's
 * types have no metrics read, so the demo makes up CPU, memory and disk series, the same numbers for
 * the same resource and instant on every call: a daily wave and a little noise around a level the
 * resource's name picks. The names listed as idle stay near zero, the oversized ones low, the busy
 * ones high, and every other resource is ordinary, so the alarms and the rightsizing have something
 * to show. Nothing of it runs in production.
 */
public final class DemoMetrics implements MetricsPort {

    public static final String CPU = "cpuPercent";
    public static final String MEMORY = "memoryPercent";
    public static final String DISK = "diskPercent";
    static final List<String> SERIES = List.of(CPU, MEMORY, DISK);
    static final String UNIT = "percent";

    /** A series' level: the middle, how far the daily wave swings, how much noise rides on it. */
    record Level(double base, double swing, double noise) {
    }

    /** How a resource behaves: the levels of its three series. */
    enum Profile {
        IDLE(new Level(2, 0.8, 0.5), new Level(6, 0.5, 0.3), new Level(12, 0.5, 0.2)),
        OVERSIZED(new Level(10, 3, 1.5), new Level(18, 3, 1.5), new Level(35, 1, 0.5)),
        BUSY(new Level(72, 14, 4), new Level(78, 4, 2), new Level(60, 2, 1)),
        NORMAL(new Level(38, 12, 4), new Level(52, 6, 3), new Level(45, 2, 1));

        final Level cpu;
        final Level memory;
        final Level disk;

        Profile(Level cpu, Level memory, Level disk) {
            this.cpu = cpu;
            this.memory = memory;
            this.disk = disk;
        }

        Level of(String series) {
            return switch (series) {
                case CPU -> cpu;
                case MEMORY -> memory;
                default -> disk;
            };
        }
    }

    private final Set<String> types;
    private final Set<String> idle;
    private final Set<String> oversized;
    private final Set<String> busy;

    /** {@code types}: the types given made-up series besides those with a metrics read; the others name resources by profile. */
    public DemoMetrics(Set<String> types, Set<String> idle, Set<String> oversized, Set<String> busy) {
        this.types = Set.copyOf(types);
        this.idle = Set.copyOf(idle);
        this.oversized = Set.copyOf(oversized);
        this.busy = Set.copyOf(busy);
    }

    @Override
    public boolean supports(TypeInfo type) {
        return type.hasMetrics() || types.contains(type.name());
    }

    @Override
    public List<String> seriesOf(TypeInfo type) {
        return SERIES;
    }

    @Override
    public Mono<ResourceMetrics> getMetrics(AccountScope scope, MetricsTarget target, TypeInfo type, MetricsWindow window) {
        return Mono.fromSupplier(() -> {
            Profile profile = profileOf(target.name());
            long step = window.step().toMillis();
            long first = Math.floorDiv(window.from().toEpochMilli() + step - 1, step) * step;
            List<MetricSeries> series = new ArrayList<>();
            for (String name : SERIES) {
                List<MetricPoint> points = new ArrayList<>();
                for (long t = first; t <= window.to().toEpochMilli(); t += step) {
                    Instant at = Instant.ofEpochMilli(t);
                    points.add(new MetricPoint(at, value(target.wfrn(), name, profile.of(name), at)));
                }
                series.add(new MetricSeries(name, UNIT, points));
            }
            return new ResourceMetrics(target.wfrn(), window.from(), window.to(), window.stepText(), series, "demo", "synthetic");
        });
    }

    Profile profileOf(String name) {
        if (name == null) {
            return Profile.NORMAL;
        }
        if (idle.contains(name)) {
            return Profile.IDLE;
        }
        if (oversized.contains(name)) {
            return Profile.OVERSIZED;
        }
        return busy.contains(name) ? Profile.BUSY : Profile.NORMAL;
    }

    /** The made-up value: the level, a wave that peaks at 15:00 UTC, noise fixed by the resource, the series and the minute. */
    static double value(String wfrn, String series, Level level, Instant at) {
        double hour = Math.floorMod(at.getEpochSecond(), 86_400L) / 3600.0;
        double wave = Math.sin(2 * Math.PI * (hour - 9) / 24);
        double v = level.base() + level.swing() * wave + level.noise() * noise(wfrn, series, Math.floorDiv(at.getEpochSecond(), 60L));
        return Math.round(Math.max(0, Math.min(100, v)) * 100.0) / 100.0;
    }

    /** A number in [-1, 1) that only the resource, the series and the minute decide (SplitMix64's finaliser). */
    static double noise(String wfrn, String series, long minute) {
        long h = wfrn.hashCode() * 0x9E3779B97F4A7C15L ^ series.hashCode() * 0xC2B2AE3D27D4EB4FL ^ minute * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h = h ^ (h >>> 31);
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }
}
