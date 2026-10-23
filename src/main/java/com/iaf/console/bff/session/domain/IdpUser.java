package com.iaf.console.bff.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** What a verified ID token says about the person who signed in. */
public record IdpUser(String issuer, String subject, String email, String name, List<String> groups, Instant authTime) {

    public IdpUser {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
