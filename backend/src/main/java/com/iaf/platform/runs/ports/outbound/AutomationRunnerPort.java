package com.iaf.platform.runs.ports.outbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;

import reactor.core.publisher.Mono;

/**
 * Runs the integration steps that are not a provider action: {@code call: ansible} (ansible-runner
 * in an execution environment), {@code call: terraform} (plan/apply/destroy with per-job state),
 * {@code call: http} (a raw request), {@code call: idp} (the IDP tile, mock until wired),
 * {@code call: activity} (a team-owned Temporal worker), {@code script} and {@code notify}.
 * The demo adapter simulates each one and answers from the matching provider sample when a
 * provider file describes the playbook or module.
 */
public interface AutomationRunnerPort {

    /** {@code with} is the step's parameters with every expression already resolved. */
    record RunRequest(String jobId, int seq, StepType type, Step step, JsonNode with, String environment, boolean dryRun, boolean checkMode) {
    }

    Mono<JsonNode> run(RunRequest request);

    boolean supports(StepType type);
}
