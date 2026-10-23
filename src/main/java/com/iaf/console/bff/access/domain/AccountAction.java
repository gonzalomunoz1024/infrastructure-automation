package com.iaf.console.bff.access.domain;

import java.util.List;

/**
 * An action on the account's own console records that the session view decides up front, so the
 * console can grey a control before anyone presses it: identity and access management writes,
 * asking for access or a higher quota, and reading or deciding the account's access requests.
 * The key is {@code <kind>:<verb>} in the identity service's kebab-case kinds. The action decided
 * is the identity service's own ({@code iam:<verb>} for users, groups, roles, policies and access
 * keys, the kind's own for quotas and access requests) on every record of the kind, so a policy
 * that allows one record only is not reflected here; the identity service still decides each
 * call. A person's own access keys need none of these.
 */
public record AccountAction(String key, String action, String kind) {

    public static final List<AccountAction> ALL = List.of(
            iam("user", "create"), iam("user", "update"), iam("user", "delete"),
            iam("group", "create"), iam("group", "update"), iam("group", "delete"),
            iam("role", "create"), iam("role", "update"), iam("role", "delete"),
            iam("policy", "create"), iam("policy", "update"), iam("policy", "delete"),
            iam("access-key", "create"), iam("access-key", "update"), iam("access-key", "delete"),
            own("quota", "request-increase"),
            own("access-request", "create"), own("access-request", "list"), own("access-request", "decide"));

    private static AccountAction iam(String kind, String verb) {
        return new AccountAction(kind + ":" + verb, "iam:" + verb, kind);
    }

    private static AccountAction own(String kind, String verb) {
        return new AccountAction(kind + ":" + verb, kind + ":" + verb, kind);
    }

    /** The check of this action on every record of its kind in an account. */
    public AccessCheck check(String partition, String accountId) {
        return new AccessCheck(action, AccessCheck.iamPattern(partition, accountId, kind));
    }
}
