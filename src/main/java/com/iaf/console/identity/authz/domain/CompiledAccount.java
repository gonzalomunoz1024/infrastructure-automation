package com.iaf.console.identity.authz.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One account as the authorization data sees it (the shape of the OPA bundle's data, one entry
 * of {@code data.console.accounts}): users with their group names and time-boxed roles, groups
 * with their role names, roles with their policy ids and permission boundary, policies with their
 * statements, the ids of the organization's guardrails that apply to it, and its version.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompiledAccount(String status, Map<String, CompiledUser> users, Map<String, CompiledGroup> groups, Map<String, CompiledRole> roles,
        Map<String, CompiledPolicy> policies, List<String> guardrails, long version) {

    public CompiledAccount(String status, Map<String, CompiledUser> users, Map<String, CompiledGroup> groups, Map<String, CompiledRole> roles,
            Map<String, CompiledPolicy> policies, long version) {
        this(status, users, groups, roles, policies, null, version);
    }

    /** This account with one more (or replaced) user: a probe for "what could a member of these groups do". */
    public CompiledAccount withUser(String userId, CompiledUser user) {
        Map<String, CompiledUser> copy = new java.util.HashMap<>(users);
        copy.put(userId, user);
        return new CompiledAccount(status, Map.copyOf(copy), groups, roles, policies, guardrails, version);
    }

    /** This account with one more (or replaced) role and policies: a probe for a draft the simulator answers about. */
    public CompiledAccount withRole(String name, CompiledRole role, Map<String, CompiledPolicy> extraPolicies) {
        Map<String, CompiledRole> rolesCopy = new java.util.HashMap<>(roles);
        rolesCopy.put(name, role);
        Map<String, CompiledPolicy> policiesCopy = new java.util.HashMap<>(policies);
        policiesCopy.putAll(extraPolicies);
        return new CompiledAccount(status, users, groups, Map.copyOf(rolesCopy), Map.copyOf(policiesCopy), guardrails, version);
    }

    /** This account with one more (or replaced) group: a probe for the simulator. */
    public CompiledAccount withGroup(String name, CompiledGroup group) {
        Map<String, CompiledGroup> copy = new java.util.HashMap<>(groups);
        copy.put(name, group);
        return new CompiledAccount(status, users, Map.copyOf(copy), roles, policies, guardrails, version);
    }

    /**
     * A person in the account: their state, their groups by name, and the roles they hold for a
     * while (an approved elevation or a break-glass grant), each until an instant in epoch seconds.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledUser(String status, List<String> groups, List<CompiledGrant> grants) {

        public CompiledUser(String status, List<String> groups) {
            this(status, groups, null);
        }

        public CompiledUser {
            groups = groups == null ? List.of() : List.copyOf(groups);
            grants = grants == null || grants.isEmpty() ? null : List.copyOf(grants);
        }

        public List<CompiledGrant> grantsOrEmpty() {
            return grants == null ? List.of() : grants;
        }
    }

    /** A role held for a while: {@code kind} is {@code elevation} or {@code break-glass}, {@code until} epoch seconds. */
    public record CompiledGrant(String role, long until, String kind) {
    }

    public record CompiledGroup(List<String> roles) {
    }

    /** A role: its policies in order and, when set, the policy that bounds what they may grant. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompiledRole(List<String> policies, String boundary) {

        public CompiledRole(List<String> policies) {
            this(policies, null);
        }
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
        guardrails = guardrails == null || guardrails.isEmpty() ? null : List.copyOf(guardrails);
    }

    public List<String> guardrailsOrEmpty() {
        return guardrails == null ? List.of() : guardrails;
    }
}
