package com.iaf.provider.sdk;

/**
 * The platform job a call belongs to.
 *
 * @param id       the job id, also the id of its Temporal workflow
 * @param workflow the workflow's name
 * @param version  the workflow's version
 * @param traceId  the job's trace id, for logs (may be null)
 */
public record JobInfo(String id, String workflow, String version, String traceId) {
}
