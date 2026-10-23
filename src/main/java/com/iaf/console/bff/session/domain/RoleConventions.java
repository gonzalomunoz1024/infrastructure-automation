package com.iaf.console.bff.session.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The managed roles a membership implies when the identity service names only groups: a group
 * called like an admin, operator, auditor or viewer group grants that role; a member of an account
 * with no such group is a viewer. The identity service's own {@code roles} list wins when present.
 */
public final class RoleConventions {

    public static final String VIEWER = "viewer";
    public static final String OPERATOR = "operator";
    public static final String ADMIN = "admin";
    public static final String AUDITOR = "auditor";

    public static final List<String> MANAGED = List.of(VIEWER, OPERATOR, ADMIN, AUDITOR);

    private RoleConventions() {
    }

    public static List<String> fromGroups(List<String> groups) {
        List<String> roles = new ArrayList<>();
        for (String group : groups == null ? List.<String>of() : groups) {
            String g = group.toLowerCase(Locale.ROOT);
            if (g.contains("admin") || g.contains("owner")) {
                add(roles, ADMIN);
            } else if (g.contains("operator") || g.contains("developer") || g.equals("ops") || g.endsWith("-ops") || g.contains("dba")) {
                add(roles, OPERATOR);
            } else if (g.contains("auditor") || g.contains("audit")) {
                add(roles, AUDITOR);
            }
        }
        if (roles.isEmpty()) {
            roles.add(VIEWER);
        }
        return List.copyOf(roles);
    }

    /** The strongest managed role of a list, for the header's badge. */
    public static String strongest(List<String> roles) {
        if (roles.contains(ADMIN)) {
            return ADMIN;
        }
        if (roles.contains(OPERATOR)) {
            return OPERATOR;
        }
        if (roles.contains(AUDITOR)) {
            return AUDITOR;
        }
        return VIEWER;
    }

    private static void add(List<String> roles, String role) {
        if (!roles.contains(role)) {
            roles.add(role);
        }
    }
}
