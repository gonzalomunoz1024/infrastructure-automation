package com.iaf.console.fake.identity.domain;

import java.util.Collection;

/**
 * The caller-scoped read rules (P-18): a resource is visible when it is public, owned by one of
 * the caller's teams or in the caller's account; a job when the caller requested it, owns the
 * workflow, awaits its approval or is in its account; admins and unbound service accounts see all.
 */
public final class ReadScopes {

    private ReadScopes() {
    }

    public static boolean seesResource(Caller caller, String owner, String visibility, String accountId) {
        if (caller.seesEverything()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return "public".equals(visibility) || caller.consoleAccount().equals(accountId);
        }
        return "public".equals(visibility) || (owner != null && caller.teams().contains(owner))
                || (caller.accountId() != null && caller.accountId().equals(accountId));
    }

    public static boolean seesJob(Caller caller, String requester, String workflowOwner, String accountId, Collection<String> approvalGroups) {
        if (caller.seesEverything()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return caller.consoleAccount().equals(accountId);
        }
        if (caller.id().equals(requester) || (workflowOwner != null && caller.teams().contains(workflowOwner))) {
            return true;
        }
        if (approvalGroups != null) {
            for (String group : approvalGroups) {
                if (caller.inGroup(group)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the caller owns a job, and so sees its input and output unmasked. */
    public static boolean ownsJob(Caller caller, String requester, String accountId) {
        if (caller.seesEverything()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return caller.consoleAccount().equals(accountId);
        }
        return caller.id().equals(requester);
    }

    /** Whether the caller may steer a job: its requester, the workflow's owner team, approvers and admins. */
    public static boolean steersJob(Caller caller, String requester, String workflowOwner, Collection<String> rbacRoles,
            Collection<String> approvalGroups, String accountId) {
        if (caller.admin()) {
            return true;
        }
        if (caller.isConsoleAccount()) {
            return caller.consoleAccount().equals(accountId);
        }
        if (caller.requester().equals(requester) || (workflowOwner != null && caller.teams().contains(workflowOwner))) {
            return true;
        }
        if (rbacRoles != null) {
            for (String role : rbacRoles) {
                if (caller.roles().contains(role)) {
                    return true;
                }
            }
        }
        if (approvalGroups != null) {
            for (String group : approvalGroups) {
                if (caller.inGroup(group)) {
                    return true;
                }
            }
        }
        return false;
    }
}
