package com.iaf.console.drift.rightsizing.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.drift.drift.domain.Money;

/** The account's open recommendations at a glance ({@code RecommendationsSummary}): how many, of which kind, what they would save a month, and when the last pass read the account. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecommendationsSummary(int open, Map<String, Integer> byKind, Money monthlySaving, Instant lastPassAt) {
}
