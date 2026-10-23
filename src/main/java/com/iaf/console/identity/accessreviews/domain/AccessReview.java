package com.iaf.console.identity.accessreviews.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An access review campaign: every person and group of the account's active memberships when it
 * started, for the account's admins to keep or revoke before the due date. It is {@code open}
 * until every item is decided ({@code closed}), its due date passes ({@code closed}, its pending
 * items {@code expired}) or someone cancels it ({@code cancelled}, its pending items stay
 * pending). An account has at most one open campaign. {@code version} grows by one per change and
 * the {@code AccessReviewChanged} events carry it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessReview(String reviewId, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountId, String name, Status status,
        Trigger trigger, String startedBy, Instant startedAt, Instant dueAt, Instant closedAt, Counts counts, long version) {

    public enum Status {
        open, closed, cancelled
    }

    /** Who started it: a person or a console service ({@code manual}), or the quarterly sweep ({@code scheduled}). */
    public enum Trigger {
        manual, scheduled
    }

    /** How many items the campaign has, by decision. */
    public record Counts(int total, int kept, int revoked, int expired, int pending) {

        public static final Counts NONE = new Counts(0, 0, 0, 0, 0);

        public static Counts of(Map<AccessReviewItem.Decision, Long> byDecision) {
            int kept = count(byDecision, AccessReviewItem.Decision.keep);
            int revoked = count(byDecision, AccessReviewItem.Decision.revoke);
            int expired = count(byDecision, AccessReviewItem.Decision.expired);
            int pending = count(byDecision, AccessReviewItem.Decision.pending);
            return new Counts(kept + revoked + expired + pending, kept, revoked, expired, pending);
        }

        public static Counts of(List<AccessReviewItem> items) {
            Map<AccessReviewItem.Decision, Long> byDecision = new EnumMap<>(AccessReviewItem.Decision.class);
            for (AccessReviewItem item : items) {
                byDecision.merge(item.decision(), 1L, Long::sum);
            }
            return of(byDecision);
        }

        private static int count(Map<AccessReviewItem.Decision, Long> byDecision, AccessReviewItem.Decision decision) {
            Long n = byDecision.get(decision);
            return n == null ? 0 : Math.toIntExact(n);
        }
    }

    public AccessReview {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(startedBy, "startedBy");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(dueAt, "dueAt");
        counts = counts == null ? Counts.NONE : counts;
    }

    /** A new campaign over these items; one with nothing to review is closed at once. */
    public static AccessReview start(String reviewId, String accountId, String name, Trigger trigger, String startedBy, Instant now, Instant dueAt,
            Counts counts) {
        boolean nothingToReview = counts.pending() == 0;
        return new AccessReview(reviewId, accountId, name, nothingToReview ? Status.closed : Status.open, trigger, startedBy, now, dueAt,
                nothingToReview ? now : null, counts, 1);
    }

    public boolean isOpen() {
        return status == Status.open;
    }

    /** Still open at or past its due date: what the sweep closes. */
    public boolean isDue(Instant now) {
        return isOpen() && !now.isBefore(dueAt);
    }

    /** After a decision: the counts as they stand; an open campaign with nothing pending is closed. */
    public AccessReview counted(Counts latest, Instant now) {
        boolean closes = isOpen() && latest.pending() == 0;
        return new AccessReview(reviewId, accountId, name, closes ? Status.closed : status, trigger, startedBy, startedAt, dueAt, closes ? now : closedAt,
                latest, version + 1);
    }

    /** Past its due date: closed, with the counts once its pending items expired. */
    public AccessReview expired(Counts latest, Instant now) {
        return new AccessReview(reviewId, accountId, name, Status.closed, trigger, startedBy, startedAt, dueAt, now, latest, version + 1);
    }

    /** Cancelled: over, its pending items left pending. */
    public AccessReview cancelled(Instant now) {
        return new AccessReview(reviewId, accountId, name, Status.cancelled, trigger, startedBy, startedAt, dueAt, now, counts, version + 1);
    }
}
