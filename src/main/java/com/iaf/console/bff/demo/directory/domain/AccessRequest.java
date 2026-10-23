package com.iaf.console.bff.demo.directory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** A request for a managed role, as the identity service's contract serves it. */
public record AccessRequest(
        String requestId,
        String accountId,
        Requester requester,
        String action,
        String resource,
        String role,
        String reason,
        String status,
        Instant requestedAt,
        Instant decidedAt,
        String decidedBy,
        String comment,
        List<String> approvers) {

    /** The statuses a request is served with; {@code expired} is a request still pending after {@link #EXPIRES_AFTER}. */
    public static final Set<String> STATUSES = Set.of("pending", "approved", "rejected", "expired");

    /** How long a request may stay pending before it expires. */
    public static final Duration EXPIRES_AFTER = Duration.ofDays(30);

    public record Requester(String userId, String email, String name) {
    }

    public AccessRequest decided(String decision, String by, String comment, Instant at) {
        return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, "approve".equals(decision) ? "approved" : "rejected",
                requestedAt, at, by, comment, approvers);
    }

    /** The request as it is served at {@code now}: a pending one older than {@link #EXPIRES_AFTER} is expired. */
    public AccessRequest served(Instant now) {
        if ("pending".equals(status) && requestedAt.plus(EXPIRES_AFTER).isBefore(now)) {
            return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, "expired", requestedAt,
                    requestedAt.plus(EXPIRES_AFTER), null, null, approvers);
        }
        return this;
    }

    public boolean open() {
        return "pending".equals(status);
    }
}
