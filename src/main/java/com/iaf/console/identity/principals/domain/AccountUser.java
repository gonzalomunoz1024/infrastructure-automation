package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A user as an account serves them: the person plus their state and groups (by name) there. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountUser(String userId, String email, String name, UserStatus status, String issuer, String subject, List<String> groups, long version,
        Instant createdAt, Instant lastSignInAt) {

    public static AccountUser of(User user, Membership membership, List<String> groupNames) {
        UserStatus status = user.status() == UserStatus.suspended ? UserStatus.suspended : membership.status();
        return new AccountUser(user.userId(), user.email(), user.name(), status, user.issuer(), user.subject(), groupNames, membership.version(),
                membership.joinedAt(), user.lastSignInAt());
    }
}
