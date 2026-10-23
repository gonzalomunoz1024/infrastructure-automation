package com.iaf.console.bff.jobs.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer to deciding a request's approvals at once: the decision, the approvals it was
 * recorded on, and those left pending with the reason (another group's, the person's own request,
 * or refused by the platform).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BulkDecision(String decision, List<String> decided, List<Left> left) {

    public BulkDecision {
        decided = decided == null ? List.of() : List.copyOf(decided);
        left = left == null ? List.of() : List.copyOf(left);
    }

    /** An approval this call did not decide, and why. */
    public record Left(String approvalId, String jobId, String reason) {
    }
}
