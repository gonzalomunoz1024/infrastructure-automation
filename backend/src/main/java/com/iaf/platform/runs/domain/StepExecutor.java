package com.iaf.platform.runs.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.Step;

/**
 * The only thing the interpreter lets touch the world. The in-memory engine implements it with
 * the outbound ports and per-job latches; the Temporal adapter with activities, child workflows
 * and {@code Workflow.await}; dry runs and the demo seed with recorded samples.
 *
 * <p>Every call may throw a {@link StepFailure} (the step failed, classified) or a
 * {@link JobCancelled}. Blocking is expected: the walker runs on its own thread.</p>
 */
public interface StepExecutor {

    /** {@code call: provider}. {@code input} is resolved. Bounded by {@code timeout} (null = the executor's default). */
    JsonNode callProvider(JobContext ctx, Step step, String provider, String action, JsonNode input, Duration timeout);

    /** {@code call: http | ansible | terraform | idp | activity}: the resolved {@code with}. */
    JsonNode runAutomation(JobContext ctx, Step step, JsonNode with, Duration timeout);

    /**
     * {@code run}: the child workflow {@code workflow} in the version range {@code version}, as a
     * child job. Returns {@code { jobId, status, outputs, resources }} ({@link ChildResults#of},
     * with the resources the child's register steps recorded: a parent that asked for a resource to
     * be built first takes its id from there) and registers the child in the context.
     * A child that does not succeed is a {@link ChildFailure}, which the walker turns into the
     * step's output when the step says {@code onFailure: continue}.
     */
    JsonNode runChild(JobContext ctx, Step step, String workflow, String version, JsonNode input, Duration timeout);

    /** Create an approval and return its id. */
    String requestApproval(JobContext ctx, Step step, ApprovalRequest request);

    /** Wait for the decision on an approval; empty when {@code timeout} passed without one. */
    Optional<ApprovalDecision> awaitDecision(JobContext ctx, Step step, String approvalId, Duration timeout);

    /** The approval's {@code escalation.after} passed: tell {@code to}. */
    void escalateApproval(JobContext ctx, Step step, String approvalId, String to);

    /** The job needs attention: publish it and wait for the decision; empty when the attention timeout passed. */
    Optional<AttentionDecision> awaitAttention(JobContext ctx, Step step, Attention attention);

    /** {@code wait} and retry backoff. */
    void sleep(JobContext ctx, Duration duration);

    /** {@code use}: the resource as JSON ({@code id, type, name, status, attributes, ...}). */
    JsonNode useResource(JobContext ctx, Step step, UseRequest request, Duration timeout);

    /** {@code register}: the resource as JSON after the write. */
    JsonNode registerResource(JobContext ctx, Step step, RegisterRequest request);

    /** {@code notify}: {@code { delivered, channel, message }}. */
    JsonNode notify(JobContext ctx, Step step, String channel, String message);

    /** {@code script}: the value the script returned. */
    JsonNode script(JobContext ctx, Step step, String language, String code, JsonNode input, Duration timeout);

    /** A step started, waits, succeeded, failed, was skipped or compensated. */
    void onStepEvent(JobContext ctx, StepEvent event);

    /** A {@code job.log} line (compensation failures, notes). */
    default void log(JobContext ctx, String message) {
    }

    /** The clock the walker stamps step events with: wall clock here, Temporal's inside a workflow, virtual in the seed. */
    default Instant now() {
        return Instant.now();
    }

    /** Whether a cancel was requested; the walker checks before every step. */
    default boolean cancelled(JobContext ctx) {
        return false;
    }

    /**
     * How often a {@code wait until} looks at its condition again: every five seconds, unless the
     * engine pays for every look (Temporal records each one in the history, so it backs off).
     */
    default Polling polling(JobContext ctx) {
        return Polling.FIXED;
    }

    /**
     * {@code fork}: run the branches concurrently and return their results in order. The default
     * runs them one after the other (dry runs, tests); engines override with threads or
     * {@code Async.function}. The first failure wins once every branch has ended.
     */
    default List<JsonNode> runBranches(JobContext ctx, Step step, List<Supplier<JsonNode>> branches) {
        List<JsonNode> out = new ArrayList<>();
        RuntimeException first = null;
        for (Supplier<JsonNode> b : branches) {
            try {
                out.add(b.get());
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
        return out;
    }

    /** {@code approval: { group, message, timeout, escalation }}, resolved. */
    record ApprovalRequest(String group, String message, String timeout, Escalation escalation) {
        public record Escalation(String after, String to) {
        }
    }

    /** {@code use: { type, id, waitUntil, checkAccess }}, resolved. */
    record UseRequest(String type, String id, String waitUntil, boolean checkAccess) {
        public boolean waitUntilReady() {
            return "ready".equals(waitUntil);
        }
    }

    /** {@code register: { type, id, state, attributes, builtFrom }}, resolved. */
    record RegisterRequest(String type, String id, String state, JsonNode attributes, List<String> builtFrom) {
    }
}
