package com.iaf.platform.runs.adapters.outbound.temporal;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.JobStep.StepError;
import com.iaf.platform.runs.domain.JobStep.StepStatus;
import com.iaf.platform.runs.domain.StepEvent;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

/**
 * A {@link StepEvent} as it travels to the publishing activity: the step's id, name and type
 * instead of the whole step (a container step would otherwise ship its subtree with every event).
 * {@link #toEvent()} rebuilds what {@code JobEvents.step} reads.
 */
public record StepEventData(
        String step,
        String name,
        StepType type,
        StepStatus status,
        int attempt,
        JsonNode input,
        JsonNode output,
        StepError error,
        Instant at,
        Long durationMs,
        Integer items,
        boolean replayed) {

    public static StepEventData of(StepEvent e) {
        return new StepEventData(e.step().id(), e.step().name(), e.step().type(), e.status(), e.attempt(), e.input(), e.output(), e.error(), e.at(),
                e.durationMs(), e.items(), e.replayed());
    }

    public StepEvent toEvent() {
        return new StepEvent(Step.simple(step, name, type, null), status, attempt, input, output, error, at, durationMs, items, replayed);
    }
}
