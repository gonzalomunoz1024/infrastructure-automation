package com.iaf.console.bff.jobs.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.platform.model.Approval;

/** A platform approval as the contract's {@code Approval}, with whether the session's user may decide it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalView(
        String approvalId,
        String jobId,
        String workflow,
        String step,
        String group,
        String message,
        Instant requestedAt,
        String timeout,
        String status,
        String decidedBy,
        Instant decidedAt,
        String comment,
        Object preview,
        Boolean canDecide) {

    /** The view for a user: they may decide when the approval is pending, they are in its group, and the account's policies allow deciding. */
    public static ApprovalView of(Approval a, List<String> userGroups, boolean policyAllowsDeciding) {
        boolean canDecide = policyAllowsDeciding && "pending".equals(a.status()) && a.group() != null && userGroups.contains(a.group());
        return new ApprovalView(a.id(), a.jobId(), a.workflow(), a.step(), a.group(), a.message(), a.requestedAt(), a.timeout(), a.status(), a.decidedBy(),
                a.decidedAt(), a.comment(), a.preview(), canDecide);
    }
}
