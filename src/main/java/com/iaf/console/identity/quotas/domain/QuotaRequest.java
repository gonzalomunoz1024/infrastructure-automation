package com.iaf.console.identity.quotas.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An ask for a higher limit, open until the platform team sets the key's limit (approved when the
 * new limit reaches what was asked, rejected when it stays below) or the requester withdraws it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaRequest(String requestId, String accountId, String quotaKey, Integer currentLimit, int requestedLimit, String reason, Status status,
        String requestedBy, Instant requestedAt, Instant decidedAt, String decidedBy, String comment, long version) {

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
        return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, Status.open, requestedBy, now, null, null, null, 1);
    }

    public QuotaRequest decided(Status decision, String by, String decisionComment, Instant now) {
        return new QuotaRequest(requestId, accountId, quotaKey, currentLimit, requestedLimit, reason, decision, requestedBy, requestedAt, now, by,
                decisionComment, version + 1);
    }
}
