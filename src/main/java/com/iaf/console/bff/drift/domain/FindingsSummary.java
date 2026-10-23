package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The account's open findings counted by type and severity, for the findings page's header and
 * the navigation badge: any member may read them, so they come back as the drift service counts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record FindingsSummary(int open, Map<String, Integer> byType, Map<String, Integer> bySeverity, Instant lastScanAt) {

    public FindingsSummary {
        byType = byType == null ? Map.of() : Map.copyOf(byType);
        bySeverity = bySeverity == null ? Map.of() : Map.copyOf(bySeverity);
    }
}
