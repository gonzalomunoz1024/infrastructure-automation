package com.iaf.console.bff.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One account a user belongs to, with the groups and managed roles they hold there.
 * {@code elevatedUntil} is when the earliest of the roles held there for a while (an elevation or
 * break-glass access) ends, as {@code accounts[].elevations[].until} of {@code principals:resolve}
 * says; null when the person holds none there.
 */
public record AccountMembership(String accountId, String name, String status, String defaultRegion, List<String> groups, List<String> roles,
        Instant elevatedUntil) {

    public AccountMembership {
        Objects.requireNonNull(accountId, "accountId");
        name = name == null ? accountId : name;
        status = status == null ? "active" : status;
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** A membership without time-boxed roles. */
    public AccountMembership(String accountId, String name, String status, String defaultRegion, List<String> groups, List<String> roles) {
        this(accountId, name, status, defaultRegion, groups, roles, null);
    }

    public boolean holds(String role) {
        return roles.contains(role);
    }
}
