package com.iaf.console.drift.drift.domain;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The open findings of an account counted by type and severity, with the last time one of its resources was read. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FindingsSummary(long open, Map<String, Long> byType, Map<String, Long> bySeverity, Instant lastScanAt) {

    public FindingsSummary {
        byType = byType == null ? Map.of() : Map.copyOf(new TreeMap<>(byType));
        bySeverity = bySeverity == null ? Map.of() : Map.copyOf(new TreeMap<>(bySeverity));
    }

    public FindingsSummary withLastScanAt(Instant at) {
        return new FindingsSummary(open, byType, bySeverity, at);
    }
}
