package com.iaf.platform.runs.adapters.outbound.live;

import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.runs.domain.RecordedWorld;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * Production {@code call: activity} steps outside Temporal: an activity runs on the team's own
 * worker, which only the Temporal engine schedules work on (the workflow sends it to the step's
 * task queue itself, so this runner never sees it there). Without the temporal profile the step
 * fails with a message that says so, instead of the demo runner's canned answer.
 */
@Component
@Profile("live")
@Order(0)
public class WorkerActivityRunner implements AutomationRunnerPort {

    @Override
    public boolean supports(StepType type) {
        return type == StepType.ACTIVITY;
    }

    @Override
    public Mono<JsonNode> run(RunRequest request) {
        JsonNode with = request.with();
        String activity = with == null ? null : with.path("activity").asText(null);
        String queue = with == null ? null : with.path("taskQueue").asText(null);
        if (request.dryRun()) {
            // a preview never reaches the worker: it reads like the recorded answer
            return Mono.just(RecordedWorld.automation(List.of(), StepType.ACTIVITY, with, request.jobId(), request.seq(), false).output());
        }
        return Mono.error(new ProviderCallException("unsupported", "Activity " + activity + " runs on the team's own Temporal worker (task queue " + queue
                + "), so it only runs on the Temporal engine. Start the backend with the temporal profile (platform.execution.mode: temporal).",
                false, 0));
    }
}
