package com.iaf.console.bff.live.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One update for open pages: a state or drift event's type, {@code job.updated}, or
 * {@code budget.alert} (a budget crossed a threshold), with the resource it is about.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LiveEvent(String type, String wfrn, Instant at, Long version, String key, JsonNode data) {

    public static final String JOB_UPDATED = "job.updated";
    public static final String TYPES_CHANGED = "types.changed";
    public static final String REGION_CHANGED = "region.changed";
    public static final String RESYNC = "resync";
    /** A budget's spend crossed one of its thresholds: the cost service's {@code BudgetThresholdCrossed}, its data as it was. */
    public static final String BUDGET_ALERT = "budget.alert";

    public LiveEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(at, "at");
    }
}
