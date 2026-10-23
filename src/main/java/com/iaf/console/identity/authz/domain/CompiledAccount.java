package com.iaf.console.identity.authz.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One account as the authorization data sees it (the shape of the OPA bundle's data, one entry
 * of {@code data.console.accounts}): users with their group names, groups with their role names,
 * roles with their policy ids, policies with their statements, and the account's version.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompiledAccount(String status, Map<String, CompiledUser> users, Map<String, CompiledGroup> groups, Map<String, CompiledRole> roles,
        Map<String, CompiledPolicy> policies, long version) {

    /** This account with one more (or replaced) user: a probe for "what could a member of these groups do". */
    public CompiledAccount withUser(String userId, CompiledUser user) {
        Map<String, CompiledUser> copy = new java.util.HashMap<>(users);
        copy.put(userId, user);
        return new CompiledAccount(status, Map.copyOf(copy), groups, roles, policies, version);
    }

    public record CompiledUser(String status, List<String> groups) {
    }

    public record CompiledGroup(List<String> roles) {
    }

    public record CompiledRole(List<String> policies) {
    }

    public record CompiledPolicy(String name, List<CompiledStatement> statements) {
    }

    /** A statement as the data holds it: every field present, the sid always set. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledStatement(String sid, String effect, List<String> actions, List<String> notActions, List<String> resources,
            Map<String, Object> conditions) {
    }

    public CompiledAccount {
        users = users == null ? Map.of() : Map.copyOf(users);
        groups = groups == null ? Map.of() : Map.copyOf(groups);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
        policies = policies == null ? Map.of() : Map.copyOf(policies);
    }
}
