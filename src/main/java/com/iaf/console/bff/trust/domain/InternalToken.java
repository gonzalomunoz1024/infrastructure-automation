package com.iaf.console.bff.trust.domain;

import java.util.List;
import java.util.Objects;

/**
 * What an internal JWT says: the service it is for, who acts ({@code sub}), the account and region
 * when a session is behind the call, and the user's groups. {@code acct} is absent on a token of the
 * BFF acting on its own, which the other services treat as the console-internal principal.
 */
public record InternalToken(String audience, String subject, String accountId, String region, List<String> groups) {

    public InternalToken {
        Objects.requireNonNull(audience, "audience");
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
        if (audience.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("an internal token needs an audience and a subject");
        }
    }

    /** A token of the BFF itself, for calls no session is behind (resolving a principal, reading the feed). */
    public static InternalToken service(String audience, String serviceId) {
        return new InternalToken(audience, serviceId, null, null, List.of());
    }

    /** A token for a user's session in an account. */
    public static InternalToken user(String audience, String userId, String accountId, String region, List<String> groups) {
        return new InternalToken(audience, userId, accountId, region, groups);
    }

    public boolean isService() {
        return accountId == null;
    }
}
