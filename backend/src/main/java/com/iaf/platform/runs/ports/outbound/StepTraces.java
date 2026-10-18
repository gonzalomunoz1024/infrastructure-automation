package com.iaf.platform.runs.ports.outbound;

import com.iaf.platform.common.tracing.TraceIds;

/**
 * The W3C {@code traceparent} of a step's span, for a call the step hands to code the platform
 * does not run (a team's worker, docs/architecture.md "Custom-code providers"): the worker's spans
 * then continue the job's trace under the step. The tracing slice answers it from the spans it
 * keeps open ({@code JobTracing}).
 */
public interface StepTraces {

    /** Without the step's span: a {@code traceparent} in the job's trace under a span id made up for it. */
    StepTraces UNTRACED = (jobId, traceId, stepId) -> inTrace(traceId);

    /**
     * The {@code traceparent} of the open span of {@code stepId} in job {@code jobId} when this
     * instance traces it, else of the job's span; without either, one in the job's trace
     * ({@code traceId}) under a span id made up for it; null when {@code traceId} is no trace id.
     */
    String traceparent(String jobId, String traceId, String stepId);

    /** A {@code traceparent} in the trace {@code traceId} under a fresh span id; null when it is no trace id. */
    static String inTrace(String traceId) {
        return TraceIds.isTraceId(traceId) ? TraceIds.traceparent(traceId, TraceIds.newSpanId(), true) : null;
    }
}
