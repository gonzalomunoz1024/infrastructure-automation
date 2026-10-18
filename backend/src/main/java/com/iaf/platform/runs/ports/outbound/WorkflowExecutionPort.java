package com.iaf.platform.runs.ports.outbound;

import java.util.Map;

import com.iaf.platform.runs.domain.ApprovalDecision;
import com.iaf.platform.runs.domain.AttentionDecision;
import com.iaf.platform.runs.domain.Job;

import reactor.core.publisher.Mono;

/**
 * The engine that runs jobs. The demo adapter ({@code InMemoryWorkflowExecution}) walks the
 * interpreter in process, one thread per job, with per-job latches for approvals and attention
 * decisions; the {@code temporal} profile hands the same {@link Job} (definition snapshot included)
 * to a Temporal workflow and turns the signals into workflow signals.
 *
 * <p>A job with {@link Job#resume()} set does not start from its first step: the recorded steps
 * before {@code fromStep} are replayed (a rerun), and at {@code fromStep} the engine either waits
 * on the job's pending approval or on its pending {@link Job#attention()} (a seeded live job).
 * A job of kind {@code batch} is released in its waves; its rows run as child jobs.</p>
 *
 * <p>Every method completes when the engine accepted the request, never when the job ends. The
 * engine reports progress only through the job events ({@code job.step}, {@code job.finished}, ...).</p>
 */
public interface WorkflowExecutionPort {

    /** Start (or resume) a job asynchronously. */
    Mono<Void> start(Job job);

    /** A person decided one of the job's pending approvals. */
    Mono<Void> signalApproval(String jobId, String approvalId, ApprovalDecision decision);

    /** A person decided what to do with a job that needs attention. */
    Mono<Void> signalAttention(String jobId, AttentionDecision decision);

    /** Stop a job and the children it is waiting on; nothing is compensated. The engine publishes {@code job.cancelled} with {@code by}. */
    Mono<Void> cancel(String jobId, String by);

    /** Whether the engine currently holds the job (running or waiting). */
    Mono<Boolean> isRunning(String jobId);

    /**
     * Where the engine's own tools show the job, by name (the job view's {@code links}); none by
     * default. Computed from configuration alone: no call to the engine.
     */
    default Map<String, String> links(String jobId) {
        return Map.of();
    }
}
