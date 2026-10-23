package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A user in an account: their state there and the groups (by id) they are in. {@code version} is
 * the user's version within the account, the one the events and {@code If-Match} carry. The email
 * and name are copied from the user so an account's list can search and sort by them.
 */
public record Membership(String accountId, String userId, String email, String name, UserStatus status, List<String> groupIds, long version,
        Instant joinedAt, Instant updatedAt) {

    public Membership {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
    }

    public static Membership create(String accountId, User user, List<String> groupIds, Instant now) {
        return new Membership(accountId, user.userId(), user.email(), user.name(), UserStatus.active, groupIds, 1, now, now);
    }

    public Membership changed(String newName, UserStatus newStatus, List<String> newGroupIds, Instant now) {
        return new Membership(accountId, userId, email, newName == null ? name : newName, newStatus == null ? status : newStatus,
                newGroupIds == null ? groupIds : newGroupIds, version + 1, joinedAt, now);
    }

    public Membership withUserFields(String newEmail, String newName) {
        return new Membership(accountId, userId, newEmail, newName, status, groupIds, version, joinedAt, updatedAt);
    }

    public boolean inGroup(String groupId) {
        return groupIds.contains(groupId);
    }
}
