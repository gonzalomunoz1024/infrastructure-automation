package com.iaf.console.identity.quotas.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An ask for a higher limit, open until the platform team decides it ({@code :decide}, or a limit
 * set at or above the ask, which makes it moot) or the requester withdraws it. An approval records
 * the limit granted, which may differ from the ask. {@code version} is for optimistic writes and
 * is never served.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaRequest(String requestId, String accountId, String quotaKey, Integer currentLimit, int requestedLimit, String reason, Status status,
        Integer grantedLimit, String requestedBy, Instant requestedAt, Instant decidedAt, String decidedBy, String comment,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) long version) {

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
                1);
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
                decisionComment, version + 1);
    }
}
