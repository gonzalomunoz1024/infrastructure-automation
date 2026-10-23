package com.iaf.console.identity.accessreviews.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One item of an access review: a person, a group they held when the campaign started and the
 * role names the group carried then, and what became of it. {@code pending} until an admin keeps
 * or revokes it, or the due date expires it; a decision is final.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccessReviewItem(String itemId, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountId, String reviewId, String userId,
        String email, String name, String groupId, String groupName, List<String> roles, Decision decision, String decidedBy, Instant decidedAt,
        String comment, @JsonIgnore long version) {

    public enum Decision {
        pending, keep, revoke, expired
    }

    public AccessReviewItem {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(decision, "decision");
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static AccessReviewItem pending(String itemId, String accountId, String reviewId, String userId, String email, String name, String groupId,
            String groupName, List<String> roles) {
        return new AccessReviewItem(itemId, accountId, reviewId, userId, email, name, groupId, groupName, roles, Decision.pending, null, null, null, 1);
    }

    public boolean isPending() {
        return decision == Decision.pending;
    }

    public AccessReviewItem decided(Decision outcome, String by, String why, Instant now) {
        return new AccessReviewItem(itemId, accountId, reviewId, userId, email, name, groupId, groupName, roles, outcome, by, now, why, version + 1);
    }

    /** Back to pending: a decision whose change could not be made is undone, so the item is decided again. */
    public AccessReviewItem reopened() {
        return new AccessReviewItem(itemId, accountId, reviewId, userId, email, name, groupId, groupName, roles, Decision.pending, null, null, null,
                version + 1);
    }

    /** The same item at another stored version: what a store that keeps the version beside the record reads back. */
    public AccessReviewItem atVersion(long stored) {
        return new AccessReviewItem(itemId, accountId, reviewId, userId, email, name, groupId, groupName, roles, decision, decidedBy, decidedAt, comment,
                stored);
    }
}
