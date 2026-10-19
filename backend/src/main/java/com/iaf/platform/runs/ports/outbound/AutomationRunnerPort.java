package com.iaf.platform.runs.ports.outbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.workflows.domain.ProviderDrafts;
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

    /**
     * {@code with} is the step's parameters with every expression already resolved;
     * {@code drafts} are provider files nobody published yet that a dry run answers from before
     * the published providers (empty for a live step).
     */
    record RunRequest(String jobId, int seq, StepType type, Step step, JsonNode with, String environment, boolean dryRun, boolean checkMode,
            List<ProviderDefinition> drafts) {

        public RunRequest {
            drafts = drafts == null ? List.of() : List.copyOf(drafts);
        }

        public RunRequest(String jobId, int seq, StepType type, Step step, JsonNode with, String environment, boolean dryRun, boolean checkMode) {
            this(jobId, seq, type, step, with, environment, dryRun, checkMode, List.of());
        }

        /** The providers the samples answer from: {@code published} with the drafts standing in for the providers of their names. */
        public List<ProviderDefinition> providers(List<ProviderDefinition> published) {
            return ProviderDrafts.over(published, drafts);
        }
    }

    /**
     * What a runner tells its caller while a step runs: {@link #report} a line worth keeping in the
     * job log (a task started, a host failed, a plan summary) and {@link #heartbeat} between
     * reports to say the step is alive. Either may throw to stop the step (the job was
     * cancelled); the runner then ends its process and the error is the step's.
     */
    interface Progress {
        Progress NONE = message -> {
        };

        void report(String message);

        default void heartbeat() {
        }
    }

    Mono<JsonNode> run(RunRequest request);

    /** Run while reporting progress; a runner with nothing to report answers {@link #run(RunRequest)}. */
    default Mono<JsonNode> run(RunRequest request, Progress progress) {
        return run(request);
    }

    boolean supports(StepType type);
}
