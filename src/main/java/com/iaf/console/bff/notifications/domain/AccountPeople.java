package com.iaf.console.bff.notifications.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Who is in an account, as the identity service serves it: its active users with their
 * identity-provider addresses, the members of each group by the group's name (and by each
 * identity-provider group it takes in, which is what the platform's approval steps name), and its
 * admins: the members of every group that holds the account-admin role. Admins are decided on
 * roles, never on a group's name, as the identity contract asks.
 */
public record AccountPeople(String accountId, Map<String, Recipient> users, Map<String, Set<String>> groups, Set<String> admins) {

    /** The managed role whose holders are the account's admins. */
    public static final String ADMIN_ROLE = "account-admin";

    /** A user as {@code GET /v1/accounts/{accountId}/users} serves one; {@code roles} is there when the service serves it. */
    public record User(String userId, String email, String name, String status, List<String> groups, List<String> roles) {

        public User {
            groups = groups == null ? List.of() : List.copyOf(groups);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        boolean active() {
            return status == null || !"suspended".equals(status.toLowerCase(Locale.ROOT));
        }
    }

    /** A group as {@code GET /v1/accounts/{accountId}/groups} serves one: its roles as ids (or names), its members as user ids. */
    public record Group(String groupId, String name, List<String> roles, List<String> members, List<String> idpGroups) {

        public Group {
            roles = roles == null ? List.of() : List.copyOf(roles);
            members = members == null ? List.of() : List.copyOf(members);
            idpGroups = idpGroups == null ? List.of() : List.copyOf(idpGroups);
        }
    }

    /** A role as {@code GET /v1/accounts/{accountId}/roles} serves one. */
    public record Role(String roleId, String name) {
    }

    public AccountPeople {
        users = Map.copyOf(users);
        groups = Map.copyOf(groups);
        admins = Set.copyOf(admins);
    }

    /** No one: what an account is when its people cannot be read. */
    public static AccountPeople none(String accountId) {
        return new AccountPeople(accountId, Map.of(), Map.of(), Set.of());
    }

    public static AccountPeople of(String accountId, List<User> users, List<Group> groups, List<Role> roles) {
        Set<String> adminRoles = new LinkedHashSet<>(List.of(ADMIN_ROLE));
        for (Role role : roles) {
            if (ADMIN_ROLE.equals(role.name()) && role.roleId() != null) {
                adminRoles.add(role.roleId());
            }
        }
        Map<String, Recipient> active = new LinkedHashMap<>();
        for (User user : users) {
            if (user.userId() != null && user.active()) {
                active.put(user.userId(), new Recipient(user.userId(), user.email(), user.name()));
            }
        }
        Map<String, Set<String>> members = new LinkedHashMap<>();
        Set<String> admins = new LinkedHashSet<>();
        for (Group group : groups) {
            if (group.name() == null) {
                continue;
            }
            Set<String> of = members.computeIfAbsent(group.name(), n -> new LinkedHashSet<>());
            of.addAll(group.members());
            for (String idpGroup : group.idpGroups()) {
                members.computeIfAbsent(idpGroup, n -> new LinkedHashSet<>()).addAll(group.members());
            }
            if (group.roles().stream().anyMatch(adminRoles::contains)) {
                admins.addAll(group.members());
            }
        }
        for (User user : users) {
            for (String name : user.groups()) {
                members.computeIfAbsent(name, n -> new LinkedHashSet<>()).add(user.userId());
            }
            if (user.roles().stream().anyMatch(adminRoles::contains)) {
                admins.add(user.userId());
            }
        }
        // a member the users list does not hold (suspended, or gone since) is no one to tell
        members.values().forEach(set -> set.retainAll(active.keySet()));
        admins.retainAll(active.keySet());
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        members.forEach((name, set) -> frozen.put(name, Set.copyOf(set)));
        return new AccountPeople(accountId, active, frozen, admins);
    }

    /** The members of a group by its name (or an identity-provider group it takes in). */
    public Set<String> membersOf(String group) {
        return groups.getOrDefault(group, Set.of());
    }
}
