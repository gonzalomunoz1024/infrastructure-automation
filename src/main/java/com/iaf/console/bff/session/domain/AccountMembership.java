package com.iaf.console.bff.session.domain;

import java.util.List;
import java.util.Objects;

/** One account a user belongs to, with the groups and managed roles they hold there. */
public record AccountMembership(String accountId, String name, String status, String defaultRegion, List<String> groups, List<String> roles) {

    public AccountMembership {
        Objects.requireNonNull(accountId, "accountId");
        name = name == null ? accountId : name;
        status = status == null ? "active" : status;
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public boolean holds(String role) {
        return roles.contains(role);
    }
}
