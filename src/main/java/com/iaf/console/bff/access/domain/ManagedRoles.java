package com.iaf.console.bff.access.domain;

import java.util.List;
import java.util.Locale;

import com.iaf.console.bff.session.domain.ActionPatterns;

/**
 * The managed roles and what each allows, as the console reads them: viewer reads, operator reads
 * and runs non-destructive verbs, account-admin (shown as Admin) does everything (IAM writes and
 * deletes included), auditor reads and sees the trail. The identity service creates them in every
 * account with the groups that hold them: viewers, members (operator), owners (account-admin) and
 * auditors. Reading the account's access-request queue is left out of viewer and operator, as the
 * identity service's managed policies leave it out ({@code notActions}), and deciding a request is
 * an admin's. A viewer may still ask for access and for a higher quota, as the identity service's
 * viewer policy allows. Cost and budgets (decision 25): every role reads them ({@code cost:list},
 * {@code budgets:get}, {@code budgets:list}); exporting the chargeback ({@code cost:export}) is an
 * admin's and an auditor's, and changing budgets an admin's alone. The identity service decides
 * for real; this table answers the demo stand-in and names the role to suggest when a verb is
 * refused.
 */
public final class ManagedRoles {

    public static final String VIEWER = "viewer";
    public static final String OPERATOR = "operator";
    public static final String ADMIN = "account-admin";
    public static final String AUDITOR = "auditor";

    public static final List<String> ALL = List.of(VIEWER, OPERATOR, ADMIN, AUDITOR);

    /** The managed group that holds a managed role in every account. */
    public static String groupOf(String role) {
        return switch (role == null ? "" : role.toLowerCase(Locale.ROOT)) {
            case ADMIN -> "owners";
            case OPERATOR -> "members";
            case AUDITOR -> "auditors";
            case VIEWER -> "viewers";
            default -> role;
        };
    }

    /** The reads every role allows; {@code observe} (a resource's metrics, a discovery run) reads the provider, as the identity service's managed policies have it. */
    private static final List<String> READS = List.of("*:list", "*:get", "*:read", "*:describe", "*:search", "*:preview", "*:observe");
    private static final List<String> DESTRUCTIVE = List.of("*:delete", "*:destroy", "*:purge", "*:terminate");
    private static final List<String> IAM_WRITES = List.of("iam:create", "iam:update", "iam:delete", "iam:rotate", "iam:decide", "iam:set",
            "access-request:decide");
    private static final List<String> QUEUE = List.of("access-request:get", "access-request:list");
    /** What a viewer may ask for although it changes nothing of the account's resources. */
    private static final List<String> SELF_SERVICE = List.of("access-request:create", "quota:request-increase");
    private static final List<String> AUDIT = List.of("audit:*", "jobs:list", "jobs:get", "approvals:list", "approvals:get");
    /** Exporting the chargeback finance receives: admins and auditors. */
    private static final List<String> COST_EXPORT = List.of("cost:export");
    /** Creating, changing and deleting budgets: admins only. */
    private static final List<String> BUDGET_WRITES = List.of("budgets:create", "budgets:update", "budgets:delete");

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
                return !matchesAny(DESTRUCTIVE, action) && !matchesAny(IAM_WRITES, action) && !matchesAny(QUEUE, action)
                        && !matchesAny(COST_EXPORT, action) && !matchesAny(BUDGET_WRITES, action);
            case AUDITOR:
                return matchesAny(READS, action) || matchesAny(AUDIT, action) || matchesAny(COST_EXPORT, action);
            case VIEWER:
                return (matchesAny(READS, action) || matchesAny(SELF_SERVICE, action)) && !matchesAny(QUEUE, action);
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

    /**
     * The weakest managed role that would allow an action: auditor for the access-request queue
     * and the chargeback export, viewer for any other read and for asking for access or quota,
     * admin for a delete, an IAM write or a budget change, operator otherwise.
     */
    public static String suggestedFor(String action) {
        if (matchesAny(QUEUE, action) || matchesAny(COST_EXPORT, action)) {
            return AUDITOR;
        }
        if (matchesAny(READS, action) || matchesAny(SELF_SERVICE, action)) {
            return VIEWER;
        }
        if (matchesAny(DESTRUCTIVE, action) || matchesAny(IAM_WRITES, action) || matchesAny(BUDGET_WRITES, action)) {
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
        if (matchesAny(COST_EXPORT, action)) {
            return "AllowCostExport";
        }
        if (matchesAny(BUDGET_WRITES, action)) {
            return "AllowBudgetWrites";
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
