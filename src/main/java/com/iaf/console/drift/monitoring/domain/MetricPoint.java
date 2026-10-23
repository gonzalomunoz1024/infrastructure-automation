package com.iaf.console.drift.monitoring.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One value of a series at one instant. The value is null where the provider has a gap, and the
 * contract asks for it to be written as {@code null} rather than left out, so a chart shows the gap.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MetricPoint(Instant at, Double value) {
}
