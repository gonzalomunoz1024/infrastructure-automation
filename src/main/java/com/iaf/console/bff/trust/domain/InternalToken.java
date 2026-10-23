package com.iaf.console.bff.trust.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What an internal JWT says: the service it is for, who acts ({@code sub}), the account and region
 * when a session is behind the call, the user's groups and the managed roles they hold there
 * ({@code roles}: viewer, operator, account-admin, auditor; admin-only decisions downstream test
 * it for {@code account-admin}). {@code acct} is absent on a token of the BFF acting on its own,
 * which the other services treat as the console-internal principal. {@code notAfter}, when set,
 * is when the roles stop being current (the session's next re-read, or a time-boxed role's end):
 * the token never outlives it.
 */
public record InternalToken(String audience, String subject, String accountId, String region, List<String> groups, List<String> roles,
        Instant notAfter) {

    public InternalToken {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
        if (audience.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("an internal token needs an audience and a subject");
        }
    }

    /** A token of the BFF itself, for calls no session is behind (resolving a principal, reading the feed). */
    public static InternalToken service(String audience, String serviceId) {
        return new InternalToken(audience, serviceId, null, null, List.of(), List.of(), null);
    }

    /**
     * A token for a user's session in an account, with the groups and managed roles the membership
     * gives them there, valid no later than {@code rolesUntil}. A person's token always names the account.
     */
    public static InternalToken user(String audience, String userId, String accountId, String region, List<String> groups, List<String> roles,
            Instant rolesUntil) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("a person's token names the account they act in");
        }
        return new InternalToken(audience, userId, accountId, region, groups, roles, rolesUntil);
    }

    public boolean isService() {
        return accountId == null;
    }
}
