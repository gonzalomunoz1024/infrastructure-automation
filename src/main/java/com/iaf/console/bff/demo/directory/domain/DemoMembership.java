package com.iaf.console.bff.demo.directory.domain;

import java.util.ArrayList;
import java.util.List;

import com.iaf.console.bff.access.domain.ManagedRoles;

/** A person's place in an account: the console groups they are in and the managed roles those grant. */
public record DemoMembership(String accountId, List<String> groups, List<String> roles) {

    public DemoMembership {
        groups = groups == null ? List.of() : List.copyOf(groups);
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public DemoMembership withRole(String role) {
        if (roles.contains(role)) {
            return this;
        }
        List<String> next = new ArrayList<>(roles);
        next.add(role);
        List<String> nextGroups = new ArrayList<>(groups);
        String group = ManagedRoles.groupOf(role);
        if (!nextGroups.contains(group)) {
            nextGroups.add(group);
        }
        return new DemoMembership(accountId, nextGroups, next);
    }
}
