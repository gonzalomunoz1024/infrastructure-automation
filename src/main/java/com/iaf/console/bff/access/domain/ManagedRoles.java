package com.iaf.console.bff.access.domain;

import java.util.List;
import java.util.Locale;

import com.iaf.console.bff.session.domain.ActionPatterns;

/**
 * The managed roles and what each allows, as the console reads them: viewer reads, operator reads
 * and runs non-destructive verbs, admin does everything (IAM writes and deletes included), auditor
 * reads and sees the trail. The identity service decides for real; this table answers the demo
 * stand-in and names the role to suggest when a verb is refused.
 */
public final class ManagedRoles {

    public static final String VIEWER = "viewer";
    public static final String OPERATOR = "operator";
    public static final String ADMIN = "admin";
    public static final String AUDITOR = "auditor";

    public static final List<String> ALL = List.of(VIEWER, OPERATOR, ADMIN, AUDITOR);

    private static final List<String> READS = List.of("*:list", "*:get", "*:read", "*:describe", "*:search", "*:preview");
    private static final List<String> DESTRUCTIVE = List.of("*:delete", "*:destroy", "*:purge", "*:terminate");
    private static final List<String> IAM_WRITES = List.of("iam:create", "iam:update", "iam:delete", "iam:rotate", "iam:decide", "iam:set");
    private static final List<String> AUDIT = List.of("audit:*", "jobs:list", "jobs:get", "approvals:list", "approvals:get");

    private ManagedRoles() {
    }

    public static boolean isManaged(String role) {
        return role != null && ALL.contains(role.toLowerCase(Locale.ROOT));
    }

    /** Whether a managed role allows an action. */
    public static boolean allows(String role, String action) {
        switch (role == null ? "" : role.toLowerCase(Locale.ROOT)) {
            case ADMIN:
                return true;
            case OPERATOR:
                return !matchesAny(DESTRUCTIVE, action) && !matchesAny(IAM_WRITES, action);
            case AUDITOR:
                return matchesAny(READS, action) || matchesAny(AUDIT, action);
            case VIEWER:
                return matchesAny(READS, action);
            default:
                return false;
        }
    }

    /** Whether any of a user's roles allows an action. */
    public static boolean anyAllows(List<String> roles, String action) {
        for (String role : roles) {
            if (allows(role, action)) {
                return true;
            }
        }
        return false;
    }

    /** The weakest managed role that would allow an action: viewer for a read, admin for a delete or an IAM write, operator otherwise. */
    public static String suggestedFor(String action) {
        if (matchesAny(READS, action)) {
            return VIEWER;
        }
        if (matchesAny(DESTRUCTIVE, action) || matchesAny(IAM_WRITES, action)) {
            return ADMIN;
        }
        return OPERATOR;
    }

    /** The statement id of a managed role's policy that decides an action, for the refusal view. */
    public static String statementFor(String role, String action) {
        if (matchesAny(READS, action)) {
            return "AllowReads";
        }
        if (matchesAny(DESTRUCTIVE, action)) {
            return "AllowDestructive";
        }
        if (matchesAny(IAM_WRITES, action)) {
            return "AllowIamWrites";
        }
        return "AllowChanges";
    }

    private static boolean matchesAny(List<String> patterns, String action) {
        for (String pattern : patterns) {
            if (ActionPatterns.matches(pattern, action)) {
                return true;
            }
        }
        return false;
    }
}
