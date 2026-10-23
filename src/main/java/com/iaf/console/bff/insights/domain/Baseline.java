package com.iaf.console.bff.insights.domain;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Today's tickets, from a one-off export of the ticket queue: the median lead time of a ticket
 * ({@code leadTime}) and, where the export tells kinds of request apart, the median per workflow.
 * A request's baseline is its workflow's, else the overall one; neither set means no baseline.
 */
public record Baseline(String source, Duration leadTime, Map<String, Duration> byWorkflow) {

    public static final Baseline NONE = new Baseline(null, null, Map.of());

    public Baseline {
        byWorkflow = byWorkflow == null ? Map.of() : Map.copyOf(byWorkflow);
        source = source == null || source.isBlank() ? null : source;
        if (leadTime != null && (leadTime.isNegative() || leadTime.isZero())) {
            throw new IllegalArgumentException("a ticket baseline is a positive duration, was " + leadTime);
        }
        byWorkflow.forEach((workflow, d) -> {
            if (d == null || d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("the ticket baseline of " + workflow + " is a positive duration, was " + d);
            }
        });
    }

    public boolean configured() {
        return leadTime != null || !byWorkflow.isEmpty();
    }

    /** The baseline of one request of a workflow. */
    public Optional<Duration> of(String workflow) {
        Duration own = workflow == null ? null : byWorkflow.get(workflow);
        return Optional.ofNullable(own != null ? own : leadTime);
    }
}
