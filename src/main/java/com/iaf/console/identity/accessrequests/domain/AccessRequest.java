package com.iaf.console.identity.accessrequests.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A user's ask for a role after a refusal: what they tried, the role that would allow it, why
 * they need it, and who may decide. Pending for thirty days, then expired.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessRequest(String requestId, String accountId, Requester requester, String action, String resource, String role, String reason, Status status,
        Instant requestedAt, Instant decidedAt, String decidedBy, String comment, List<String> approvers, @JsonIgnore long version) {

    public static final Duration PENDING_FOR = Duration.ofDays(30);

    public enum Status {
        pending, approved, rejected, expired
    }

    public record Requester(String userId, String email, String name) {
    }

    public AccessRequest {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(requester, "requester");
        Objects.requireNonNull(role, "role");
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
    }

    public static AccessRequest open(String requestId, String accountId, Requester requester, String action, String resource, String role, String reason,
            List<String> approvers, Instant now) {
        return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, Status.pending, now, null, null, null, approvers, 1);
    }

    /** The status as served: a pending request past its thirty days reads as expired. */
    public Status status(Instant now) {
        if (status == Status.pending && now.isAfter(requestedAt.plus(PENDING_FOR))) {
            return Status.expired;
        }
        return status;
    }

    public AccessRequest decided(Status decision, String by, String decisionComment, Instant now) {
        return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, decision, requestedAt, now, by, decisionComment, approvers,
                version + 1);
    }

    /** Past its thirty days: what the expiry sweep stores. */
    public AccessRequest expired() {
        return new AccessRequest(requestId, accountId, requester, action, resource, role, reason, Status.expired, requestedAt, decidedAt, decidedBy, comment,
                approvers, version + 1);
    }

    public AccessRequest served(Instant now) {
        Status s = status(now);
        return s == status ? this : new AccessRequest(requestId, accountId, requester, action, resource, role, reason, s, requestedAt, decidedAt, decidedBy,
                comment, approvers, version);
    }
}
