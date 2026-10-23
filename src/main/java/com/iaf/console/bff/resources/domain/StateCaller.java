package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Objects;

/** The console user a state call is made for: the token's subject, groups and managed roles (the account and region are the scope's). */
public record StateCaller(String userId, List<String> groups, List<String> roles) {

    public StateCaller {
        Objects.requireNonNull(userId, "userId");
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
