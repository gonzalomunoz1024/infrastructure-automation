package com.iaf.console.identity.elevations.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A role of an account held for a while: a person's ask with a reason, which a rule or an account
 * admin approves (an {@code elevation}), or support or emergency access an organization admin
 * gives with a named approver (a {@code break-glass} one). Once approved it is {@code active} from
 * {@code startsAt} until {@code until}, when it stops deciding; it may end early ({@code revoked},
 * or {@code cancelled} while pending). {@code version} is for optimistic writes and not served.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Elevation(String elevationId, String accountId, Kind kind, Person user, String role, String reason, String ticket, int durationMinutes,
        Status status, Instant requestedAt, String requestedBy, Instant decidedAt, String decidedBy, String comment, Instant startsAt, Instant until,
        Instant endedAt, String endedBy, String endComment, List<String> approvers, @JsonIgnore long version) {

    public enum Kind {
        ELEVATION("elevation"), BREAK_GLASS("break-glass");

        private final String wire;

        Kind(String wire) {
            this.wire = wire;
        }

        @JsonValue
        public String wire() {
            return wire;
        }

        @JsonCreator
        public static Kind of(String wire) {
            for (Kind kind : values()) {
                if (kind.wire.equals(wire)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("kind is elevation or break-glass");
        }
    }

    public enum Status {
        pending, active, rejected, cancelled, revoked, expired
    }

    /** The person it is for, as the account knew them when it was asked for. */
    public record Person(String userId, String email, String name) {
    }

    public Elevation {
        Objects.requireNonNull(elevationId, "elevationId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
        approvers = approvers == null || approvers.isEmpty() ? null : List.copyOf(approvers);
    }

    /** A person's ask, waiting for a person who may decide it. */
    public static Elevation ask(String elevationId, String accountId, Person user, String role, String reason, int minutes, List<String> approvers,
            Instant now) {
        return new Elevation(elevationId, accountId, Kind.ELEVATION, user, role, reason, null, minutes, Status.pending, now, user.userId(), null, null, null,
                null, null, null, null, null, approvers, 1);
    }

    /** A person's ask a rule approves as it is made ({@code rule:<name>}): active from now for its duration. */
    public static Elevation approvedAtOnce(String elevationId, String accountId, Person user, String role, String reason, int minutes, String rule,
            Instant now) {
        return new Elevation(elevationId, accountId, Kind.ELEVATION, user, role, reason, null, minutes, Status.active, now, user.userId(), now, rule, null,
                now, now.plus(Duration.ofMinutes(minutes)), null, null, null, null, 1);
    }

    /** Break-glass access, active from now: given by {@code requestedBy}, approved by {@code approvedBy}. */
    public static Elevation breakGlass(String elevationId, String accountId, Person user, String role, String reason, String ticket, int minutes,
            String requestedBy, String approvedBy, Instant now) {
        return new Elevation(elevationId, accountId, Kind.BREAK_GLASS, user, role, reason, ticket, minutes, Status.active, now, requestedBy, now, approvedBy,
                null, now, now.plus(Duration.ofMinutes(minutes)), null, null, null, null, 1);
    }

    /** Approved by a person or a rule ({@code rule:<name>}): active from now for its duration. */
    public Elevation approved(String by, String decisionComment, Instant now) {
        return new Elevation(elevationId, accountId, kind, user, role, reason, ticket, durationMinutes, Status.active, requestedAt, requestedBy, now, by,
                decisionComment, now, now.plus(Duration.ofMinutes(durationMinutes)), null, null, null, null, version + 1);
    }

    public Elevation rejected(String by, String decisionComment, Instant now) {
        return new Elevation(elevationId, accountId, kind, user, role, reason, ticket, durationMinutes, Status.rejected, requestedAt, requestedBy, now, by,
                decisionComment, null, null, null, null, null, null, version + 1);
    }

    /** Ended now: a pending ask is cancelled, an active elevation revoked. */
    public Elevation ended(String by, String why, Instant now) {
        Status ending = status == Status.pending ? Status.cancelled : Status.revoked;
        return new Elevation(elevationId, accountId, kind, user, role, reason, ticket, durationMinutes, ending, requestedAt, requestedBy, decidedAt, decidedBy,
                comment, startsAt, until, now, by, why, null, version + 1);
    }

    /** What the expiry sweep stores once {@link #status(Instant, Duration)} reads expired. */
    public Elevation expired() {
        return new Elevation(elevationId, accountId, kind, user, role, reason, ticket, durationMinutes, Status.expired, requestedAt, requestedBy, decidedAt,
                decidedBy, comment, startsAt, until, endedAt, endedBy, endComment, null, version + 1);
    }

    /** The status as served: an active one past {@code until}, or a pending one past {@code pendingFor}, reads as expired. */
    public Status status(Instant now, Duration pendingFor) {
        if (status == Status.active && !now.isBefore(until)) {
            return Status.expired;
        }
        if (status == Status.pending && !now.isBefore(requestedAt.plus(pendingFor))) {
            return Status.expired;
        }
        return status;
    }

    /** Pending or active at {@code now}: what one person may have only one of per role. */
    public boolean isOpen(Instant now, Duration pendingFor) {
        Status s = status(now, pendingFor);
        return s == Status.pending || s == Status.active;
    }

    /** Whether the role decides for the person at {@code now}. */
    public boolean holds(Instant now) {
        return status == Status.active && now.isBefore(until);
    }

    public Elevation served(Instant now, Duration pendingFor) {
        Status s = status(now, pendingFor);
        return s == status ? this
                : new Elevation(elevationId, accountId, kind, user, role, reason, ticket, durationMinutes, s, requestedAt, requestedBy, decidedAt, decidedBy,
                        comment, startsAt, until, endedAt, endedBy, endComment, s == Status.expired ? null : approvers, version);
    }
}
