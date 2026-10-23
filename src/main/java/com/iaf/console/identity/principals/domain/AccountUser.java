package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A user as an account serves them: the person plus their state and groups (by name) there, with
 * the groups among them they hold through their identity provider and the mapped groups an admin
 * or an access review took away from them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountUser(String userId, String email, String name, UserStatus status, String issuer, String subject, List<String> groups, long version,
        Instant createdAt, Instant lastSignInAt, List<String> idpGroups, List<String> excludedGroups) {

    public static AccountUser of(User user, Membership membership, List<String> groupNames) {
        return new AccountUser(user.userId(), user.email(), user.name(), status(user, membership), user.issuer(), user.subject(), groupNames,
                membership.version(), membership.joinedAt(), user.lastSignInAt(), null, null);
    }

    /** The user with their groups named by {@code nameById}; a derived or excluded group the account no longer has is left out. */
    public static AccountUser of(User user, Membership membership, Map<String, String> nameById) {
        List<String> groups = membership.groupIds().stream().map(id -> nameById.getOrDefault(id, id)).toList();
        List<String> derived = membership.idpGroupIds().stream().filter(nameById::containsKey).map(nameById::get).toList();
        List<String> excluded = membership.excludedGroupIds().stream().filter(nameById::containsKey).map(nameById::get).toList();
        return new AccountUser(user.userId(), user.email(), user.name(), status(user, membership), user.issuer(), user.subject(), groups,
                membership.version(), membership.joinedAt(), user.lastSignInAt(), derived, excluded);
    }

    private static UserStatus status(User user, Membership membership) {
        return user.status() == UserStatus.suspended ? UserStatus.suspended : membership.status();
    }
}
