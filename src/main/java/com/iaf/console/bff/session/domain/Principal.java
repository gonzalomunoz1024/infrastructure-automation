package com.iaf.console.bff.session.domain;

import java.util.List;
import java.util.Objects;

/** The console user a session belongs to, as the identity service resolved them. */
public record Principal(String userId, String email, String name, List<String> groups) {

    public Principal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(email, "email");
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
