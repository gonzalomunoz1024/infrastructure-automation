package com.iaf.console.drift.monitoring.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A resource's series over a window, as {@code GET /v1/metrics} answers them: the range and the
 * step that were read, and the provider and action that answered, so a person can tell where the
 * numbers come from.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceMetrics(String wfrn, Instant from, Instant to, String step, List<MetricSeries> series, String provider, String action) {

    public ResourceMetrics {
        series = series == null ? List.of() : List.copyOf(series);
    }

    /** The series of that name, when the read answered one. */
    public Optional<MetricSeries> series(String name) {
        return series.stream().filter(s -> s.name() != null && s.name().equals(name)).findFirst();
    }
}
