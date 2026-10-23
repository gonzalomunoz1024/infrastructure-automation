package com.iaf.console.identity.iam.domain;

import java.util.List;

/**
 * What every account is born with: four managed roles ({@code viewer}, {@code operator},
 * {@code account-admin}, {@code auditor}), their policies, and four groups that hold them
 * ({@code owners}, which cannot be deleted; {@code members}, which the owning team joins at
 * sign-in; {@code viewers}; {@code auditors}). The policies grant every service of the account;
 * what a verb means is the platform's catalog.
 */
public final class ManagedIam {

    public static final String VIEWER = "viewer";
    public static final String OPERATOR = "operator";
    public static final String ACCOUNT_ADMIN = "account-admin";
    public static final String AUDITOR = "auditor";

    /** The managed roles from the least to the most permissive, the order a suggested role is searched in. */
    public static final List<String> ROLES = List.of(VIEWER, AUDITOR, OPERATOR, ACCOUNT_ADMIN);

    public static final String OWNERS = "owners";
    public static final String MEMBERS = "members";
    public static final String VIEWERS = "viewers";
    public static final String AUDITORS = "auditors";

    /** A managed role: its name, what it is for, and its policy. */
    public record ManagedRole(String name, String description, String policyName, String policyDescription, List<Statement> statements) {
    }

    /** A managed group: its name, the role it holds, and whether the owning team joins it at sign-in. */
    public record ManagedGroup(String name, String description, String role, boolean owningTeamJoins, boolean undeletable) {
    }

    private ManagedIam() {
    }

    /** The web's short role names map onto the contract's: {@code admin} is {@code account-admin}. */
    public static String canonicalRole(String role) {
        if (role == null) {
            return null;
        }
        return "admin".equals(role) ? ACCOUNT_ADMIN : role;
    }

    public static boolean isManagedRole(String role) {
        return ROLES.contains(role);
    }

    public static List<ManagedRole> roles(String partition, String accountId) {
        String everything = IamResources.everything(partition, accountId);
        List<String> all = List.of(everything);
        return List.of(
                // a person always sees their own access requests; reading the account's is for those who decide them, auditors and admins;
                // access reviews are for auditors (who read them) and admins only
                new ManagedRole(VIEWER, "Reads everything in the account", "managed-viewer", "The viewer role's policy: every read, no change",
                        List.of(Statement.allowExcept("reads", List.of("*:get", "*:list", "*:observe", "*:history", "search:query", "cost:get", "cost:list",
                                "access-request:create", "quota:request-increase"), List.of("access-request:get", "access-request:list", "access-review:*"), all))),
                new ManagedRole(OPERATOR, "Reads and every change that is not a delete, no IAM", "managed-operator",
                        "The operator role's policy: everything but deletes and IAM",
                        List.of(Statement.allowExcept("changes", List.of("*:*"), List.of("*:delete", "iam:*", "quota:set", "account:update", "access-request:get",
                                "access-request:list", "access-request:decide", "access-review:*"), all),
                                Statement.allow("iam-reads", List.of("iam:get", "iam:list"), all))),
                new ManagedRole(ACCOUNT_ADMIN, "Everything in the account, IAM and deletes included", "managed-account-admin",
                        "The account-admin role's policy: everything", List.of(Statement.allow("everything", List.of("*:*"), all))),
                new ManagedRole(AUDITOR, "Reads everything and the audit trail", "managed-auditor",
                        "The auditor role's policy: every read and the audit trail, no change",
                        List.of(Statement.allow("reads", List.of("*:get", "*:list", "*:observe", "*:history", "search:query", "cost:get", "cost:list"), all),
                                Statement.allow("audit", List.of("audit:*"), all))));
    }

    public static List<ManagedGroup> groups() {
        return List.of(
                new ManagedGroup(OWNERS, "The account's administrators", ACCOUNT_ADMIN, false, true),
                new ManagedGroup(MEMBERS, "The owning team: operators", OPERATOR, true, false),
                new ManagedGroup(VIEWERS, "Read-only users", VIEWER, false, false),
                new ManagedGroup(AUDITORS, "Auditors", AUDITOR, false, false));
    }

    /** The managed group that carries a managed role, for an approved access request. */
    public static String groupFor(String role) {
        return switch (role) {
            case ACCOUNT_ADMIN -> OWNERS;
            case OPERATOR -> MEMBERS;
            case VIEWER -> VIEWERS;
            case AUDITOR -> AUDITORS;
            default -> null;
        };
    }
}
