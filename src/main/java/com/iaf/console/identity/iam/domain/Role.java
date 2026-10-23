package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A role of an account: a named set of policies a group holds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Role(String roleId, @JsonIgnore String accountId, String name, String description, List<String> policies, boolean builtIn, long version,
        Instant createdAt, Instant updatedAt) {

    public Role {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public static Role create(String roleId, String accountId, String name, String description, List<String> policies, boolean builtIn, Instant now) {
        return new Role(roleId, accountId, name, description, policies, builtIn, 1, now, now);
    }

    public Role changed(String newName, String newDescription, List<String> newPolicies, Instant now) {
        return new Role(roleId, accountId, newName == null ? name : newName, newDescription == null ? description : newDescription,
                newPolicies == null ? policies : newPolicies, builtIn, version + 1, createdAt, now);
    }
}
