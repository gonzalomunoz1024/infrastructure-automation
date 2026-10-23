package com.iaf.console.identity.quotas.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An ask for a higher limit, open until the platform team decides it. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuotaRequest(String requestId, String accountId, String key, int limit, String reason, Status status, String requestedBy, Instant requestedAt,
        String decidedBy, Instant decidedAt, long version) {

    public enum Status {
        open, approved, denied
    }

    public static QuotaRequest open(String requestId, String accountId, String key, int limit, String reason, String requestedBy, Instant now) {
        return new QuotaRequest(requestId, accountId, key, limit, reason, Status.open, requestedBy, now, null, null, 1);
    }

    public QuotaRequest decided(Status decision, String by, Instant now) {
        return new QuotaRequest(requestId, accountId, key, limit, reason, decision, requestedBy, requestedAt, by, now, version + 1);
    }
}
