package com.iaf.console.identity.iam.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A role of an account: a named set of policies a group holds, and optionally the policy that
 * bounds what they may grant (its permission boundary, by id): an allow of the role counts only
 * where the boundary allows too.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Role(String roleId, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String accountId, String name, String description, List<String> policies,
        String boundary, boolean builtIn, long version, Instant createdAt, Instant updatedAt) {

    public Role {
        Objects.requireNonNull(roleId, "roleId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(name, "name");
        policies = policies == null ? List.of() : List.copyOf(policies);
    }

    public static Role create(String roleId, String accountId, String name, String description, List<String> policies, boolean builtIn, Instant now) {
        return new Role(roleId, accountId, name, description, policies, null, builtIn, 1, now, now);
    }

    public Role changed(String newName, String newDescription, List<String> newPolicies, Instant now) {
        return new Role(roleId, accountId, newName == null ? name : newName, newDescription == null ? description : newDescription,
                newPolicies == null ? policies : newPolicies, boundary, builtIn, version + 1, createdAt, now);
    }

    /** The same role bounded by another policy (an id), or by none when null; the version stays as it is. */
    public Role bounded(String policyId) {
        return new Role(roleId, accountId, name, description, policies, policyId, builtIn, version, createdAt, updatedAt);
    }
}
