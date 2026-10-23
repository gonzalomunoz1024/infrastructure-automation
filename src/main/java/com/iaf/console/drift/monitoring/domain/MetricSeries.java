package com.iaf.console.drift.monitoring.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One named series of a resource ({@code cpuPercent}, {@code memoryPercent}, ...) as the type's
 * metrics read maps it: its unit and its points in time order. An alarm watches one series; the
 * utilisation pass reads two.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MetricSeries(String name, String unit, List<MetricPoint> points) {

    public MetricSeries {
        points = points == null ? List.of() : List.copyOf(points);
    }

    /** The values present, gaps left out. */
    public List<Double> values() {
        List<Double> out = new ArrayList<>();
        for (MetricPoint p : points) {
            if (p.value() != null && Double.isFinite(p.value())) {
                out.add(p.value());
            }
        }
        return out;
    }

    /** The values present after {@code from} (exclusive) and up to {@code to} (inclusive). */
    public List<Double> valuesBetween(Instant from, Instant to) {
        List<Double> out = new ArrayList<>();
        for (MetricPoint p : points) {
            if (p.at() != null && p.at().isAfter(from) && !p.at().isAfter(to) && p.value() != null && Double.isFinite(p.value())) {
                out.add(p.value());
            }
        }
        return out;
    }
}
