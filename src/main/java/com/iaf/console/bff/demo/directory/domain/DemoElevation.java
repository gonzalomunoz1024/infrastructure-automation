package com.iaf.console.bff.demo.directory.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An elevation of the demo (a role held for a while, asked for and decided) or break-glass access
 * (given by an organization admin through the console), as the identity service keeps them.
 */
public record DemoElevation(
        String elevationId,
        String accountId,
        String kind,
        String userId,
        String email,
        String name,
        String role,
        String reason,
        String ticket,
        int durationMinutes,
        String status,
        Instant requestedAt,
        String requestedBy,
        Instant decidedAt,
        String decidedBy,
        String comment,
        Instant startsAt,
        Instant until,
        Instant endedAt,
        String endedBy,
        String endComment,
        List<String> approvers) {

    public static final String ELEVATION = "elevation";
    public static final String BREAK_GLASS = "break-glass";
    /** How long an ask waits for a person. */
    public static final Duration PENDING_FOR = Duration.ofHours(24);

    public DemoElevation {
        approvers = approvers == null ? List.of() : List.copyOf(approvers);
    }

    /** As served at {@code now}: an active one past its until, or a pending one past {@link #PENDING_FOR}, is expired. */
    public DemoElevation served(Instant now) {
        if ("active".equals(status) && until != null && !now.isBefore(until)) {
            return withStatus("expired");
        }
        if ("pending".equals(status) && !now.isBefore(requestedAt.plus(PENDING_FOR))) {
            return withStatus("expired");
        }
        return this;
    }

    public boolean active(Instant now) {
        return "active".equals(served(now).status());
    }

    public boolean open(Instant now) {
        String s = served(now).status();
        return "active".equals(s) || "pending".equals(s);
    }

    private DemoElevation withStatus(String next) {
        return new DemoElevation(elevationId, accountId, kind, userId, email, name, role, reason, ticket, durationMinutes, next, requestedAt, requestedBy,
                decidedAt, decidedBy, comment, startsAt, until, endedAt, endedBy, endComment, approvers);
    }

    /** Started now by a decision: active until now plus its duration. */
    public DemoElevation approved(String by, String withComment, Instant now) {
        return new DemoElevation(elevationId, accountId, kind, userId, email, name, role, reason, ticket, durationMinutes, "active", requestedAt,
                requestedBy, now, by, withComment, now, now.plus(Duration.ofMinutes(durationMinutes)), null, null, null, List.of());
    }

    public DemoElevation rejected(String by, String withComment, Instant now) {
        return new DemoElevation(elevationId, accountId, kind, userId, email, name, role, reason, ticket, durationMinutes, "rejected", requestedAt,
                requestedBy, now, by, withComment, null, null, null, null, null, List.of());
    }

    /** Ended now: a pending ask is cancelled, an active one revoked. */
    public DemoElevation ended(String by, String withComment, Instant now) {
        String next = "pending".equals(status) ? "cancelled" : "revoked";
        return new DemoElevation(elevationId, accountId, kind, userId, email, name, role, reason, ticket, durationMinutes, next, requestedAt, requestedBy,
                decidedAt, decidedBy, comment, startsAt, until, now, by, withComment, List.of());
    }
}
