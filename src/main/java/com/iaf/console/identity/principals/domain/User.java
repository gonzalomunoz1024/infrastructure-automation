package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A person, once for the whole console: found by email, or by the identity provider's issuer and
 * subject after the first sign-in. What they may do in an account is the account's membership.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record User(String userId, String email, String name, UserStatus status, String issuer, String subject, long version, Instant createdAt,
        Instant lastSignInAt) {

    public User {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(status, "status");
    }

    public static User create(String userId, String email, String name, String issuer, String subject, Instant now) {
        return new User(userId, email, name, UserStatus.active, issuer, subject, 1, now, null);
    }

    public User signedIn(String issuerSeen, String subjectSeen, String nameSeen, Instant now) {
        return new User(userId, email, nameSeen == null || nameSeen.isBlank() ? name : nameSeen, status, issuer == null ? issuerSeen : issuer,
                subject == null ? subjectSeen : subject, version, createdAt, now);
    }

    public User renamed(String newName, Instant now) {
        return new User(userId, email, newName, status, issuer, subject, version + 1, createdAt, lastSignInAt);
    }
}
