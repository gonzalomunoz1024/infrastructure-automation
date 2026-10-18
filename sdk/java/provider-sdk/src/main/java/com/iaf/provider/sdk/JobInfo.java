package com.iaf.provider.sdk;

/**
 * The platform job a call belongs to.
 *
 * @param id          the job id, also the id of its Temporal workflow
 * @param workflow    the workflow's name
 * @param version     the workflow's version
 * @param traceId     the job's trace id, for logs (may be null)
 * @param traceparent the W3C trace context of the platform's step span
 *                    ({@code 00-<trace id>-<span id>-<flags>}) the call continues; may be null
 */
public record JobInfo(String id, String workflow, String version, String traceId, String traceparent) {

    /** A job without a trace context. */
    public JobInfo(String id, String workflow, String version, String traceId) {
        this(id, workflow, version, traceId, null);
    }
}
