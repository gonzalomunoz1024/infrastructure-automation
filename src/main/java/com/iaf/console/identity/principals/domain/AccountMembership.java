package com.iaf.console.identity.principals.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One account a resolved user may use: its name, the user's groups and roles there (by name), the
 * default region, and the roles they hold there for a while ({@code elevations}, absent when none).
 * An account the user is no member of appears, with no groups, while break-glass access is open.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountMembership(String accountId, String name, List<String> groups, List<String> roles, String defaultRegion, List<Held> elevations) {

    /** A role held for a while: the elevation, the role, its kind ({@code elevation} or {@code break-glass}) and when it stops deciding. */
    public record Held(String elevationId, String role, String kind, Instant until) {
    }

    public AccountMembership {
        elevations = elevations == null || elevations.isEmpty() ? null : List.copyOf(elevations);
    }

    public AccountMembership(String accountId, String name, List<String> groups, List<String> roles, String defaultRegion) {
        this(accountId, name, groups, roles, defaultRegion, null);
    }
}
