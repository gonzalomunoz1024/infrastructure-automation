package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A resource's metric series over a range, for its Monitoring tab, as the provider's metrics read
 * gave them (P-09): one series per name the type maps (cpuPercent, memoryPercent...), one point
 * per step, a point's value null where the provider had nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceMetrics(String wfrn, Instant from, Instant to, String step, List<Series> series, String provider, String action) {

    public ResourceMetrics {
        series = series == null ? List.of() : List.copyOf(series);
    }

    /** One named series and its unit. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Series(String name, String unit, List<Point> points) {

        public Series {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    /** A value at a time; the value stays in the JSON even when null, as the contract requires it. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Point(Instant at, Double value) {
    }
}
