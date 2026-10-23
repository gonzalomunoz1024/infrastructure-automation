package com.iaf.console.bff.demo.directory.domain;

import java.time.Instant;
import java.util.List;

/** A request for a managed role, as the identity service's contract will serve it. */
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

    public record Requester(String userId, String email, String name) {
    }

    public AccessRequest decided(String decision, String by, String comment, Instant at) {
        return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, "approve".equals(decision) ? "approved" : "rejected",
                requestedAt, at, by, comment, approvers);
    }

    public boolean open() {
        return "pending".equals(status);
    }
}
