package com.iaf.console.bff.resources.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The console user a state call is made for: the token's subject, groups and managed roles (the
 * account and region are the scope's), and until when those roles are current ({@code rolesUntil},
 * which the token never outlives; null when unknown).
 */
public record StateCaller(String userId, List<String> groups, List<String> roles, Instant rolesUntil) {

    public StateCaller {
        Objects.requireNonNull(userId, "userId");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public StateCaller(String userId, List<String> groups, List<String> roles) {
        this(userId, groups, roles, null);
    }
}
