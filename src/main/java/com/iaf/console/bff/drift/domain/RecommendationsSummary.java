package com.iaf.console.bff.drift.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The open recommendations counted by kind and what they would save a month, for the Rightsizing
 * page's header; any member of the account may read it, so it comes back as the drift service counts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RecommendationsSummary(int open, Map<String, Integer> byKind, Money monthlySaving, Instant lastPassAt) {

    public RecommendationsSummary {
        byKind = byKind == null ? Map.of() : Map.copyOf(byKind);
    }
}
