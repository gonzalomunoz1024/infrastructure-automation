package com.iaf.console.bff.drift.domain;

import java.util.List;

/**
 * Who the drift service hears from: a person in their account (their internal JWT carries the
 * account, their groups and their managed roles), or the BFF on its own as the console-internal
 * principal (a token without an account, which may name any account), as the executive home does
 * for the drift numbers.
 */
public record DriftCaller(String userId, List<String> groups, List<String> roles) {

    public DriftCaller {
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** A person, as their session names them. */
    public static DriftCaller person(String userId, List<String> groups, List<String> roles) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("a person has a user id");
        }
        return new DriftCaller(userId, groups, roles);
    }

    /** The BFF itself, for reads no person is behind. */
    public static DriftCaller internalPrincipal() {
        return new DriftCaller(null, List.of(), List.of());
    }

    public boolean isInternal() {
        return userId == null;
    }
}
