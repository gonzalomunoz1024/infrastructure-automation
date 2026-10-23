package com.iaf.console.identity.quotas.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An ask for a higher limit. One that an auto-approval rule covers is approved as it is made
 * ({@code decidedBy} {@code rule:<name>}); any other is routed to the approver ({@code routedTo},
 * the platform team) and stays open until they decide it ({@code :decide}, or a limit set at or
 * above the ask, which makes it moot) or the requester withdraws it. An approval records the
 * limit granted, which may differ from the ask. {@code version} is for optimistic writes and is
 * never served.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaRequest(String requestId, String accountId, String quotaKey, Integer currentLimit, int requestedLimit, String reason, Status status,
        Integer grantedLimit, String requestedBy, Instant requestedAt, Instant decidedAt, String decidedBy, String comment, String routedTo,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) long version) {

    /** How {@code decidedBy} names an auto-approval rule. */
    public static final String RULE = "rule:";

    public enum Status {
        open, approved, rejected, withdrawn
    }

    public QuotaRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(quotaKey, "quotaKey");
        Objects.requireNonNull(status, "status");
    }

    public static QuotaRequest open(String requestId, String accountId, String quotaKey, Integer currentLimit, int requestedLimit, String reason,
            String requestedBy, Instant now) {
        return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, Status.open, null, requestedBy, now, null, null, null,
                null, 1);
    }

    /** The open request sent to whoever decides it. */
    public QuotaRequest routedTo(String approver) {
        return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, status, grantedLimit, requestedBy, requestedAt, decidedAt,
                decidedBy, comment, approver, version);
    }

    /** Whether an auto-approval rule approved it. */
    public boolean approvedByRule() {
        return status == Status.approved && decidedBy != null && decidedBy.startsWith(RULE);
    }

    /** Approved with the limit granted (the ask, or what the platform team chose). */
    public QuotaRequest approved(String by, int granted, String decisionComment, Instant now) {
        return decided(Status.approved, granted, by, decisionComment, now);
    }

    public QuotaRequest rejected(String by, String decisionComment, Instant now) {
        return decided(Status.rejected, null, by, decisionComment, now);
    }

    public QuotaRequest withdrawn(String by, Instant now) {
        return decided(Status.withdrawn, null, by, null, now);
    }

    private QuotaRequest decided(Status decision, Integer granted, String by, String decisionComment, Instant now) {
        return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, decision, granted, requestedBy, requestedAt, now, by,
                decisionComment, routedTo, version + 1);
    }
}
