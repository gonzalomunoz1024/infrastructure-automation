package com.iaf.platform.identity.domain;

import java.util.List;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/**
 * RBAC, judged in one place. A workflow's {@code policy.rbac} lists the roles that may invoke
 * it; editing and publishing belong to the owner team; approving belongs to the approver group;
 * a credential is used (sent outward by the provider wizard and the imports) by the team that
 * owns it, or by anyone when it has no owner. Admins may do everything.
 */
public final class AccessRules {

    private AccessRules() {
    }

    public static boolean canInvoke(Identity who, List<String> rbac) {
        if (who.admin() || rbac == null || rbac.isEmpty()) {
            return true;
        }
        return rbac.stream().anyMatch(r -> who.roles().contains(r) || who.teams().contains(r));
    }

    public static boolean canEdit(Identity who, String ownerTeam) {
        return who.admin() || ownerTeam == null || who.teams().contains(ownerTeam);
    }

    public static boolean canApprove(Identity who, String group) {
        return who.admin() || group == null || who.teams().contains(group) || who.roles().contains(group);
    }

    public static boolean canSeeProvider(Identity who, String ownerTeam) {
        return canEdit(who, ownerTeam);
    }

    /** Admins may use any credential; anyone else one without an owner, or one owned by a team they are in. */
    public static boolean canUseCredential(Identity who, String owner) {
        return who.admin() || owner == null || owner.isBlank() || who.teams().contains(owner);
    }

    public static void requireInvoke(Identity who, List<String> rbac, String what) {
        if (!canInvoke(who, rbac)) {
            throw new ForbiddenException(who.name() + " may not invoke " + what + " (needs one of " + rbac + ")");
        }
    }

    public static void requireEdit(Identity who, String ownerTeam, String what) {
        if (!canEdit(who, ownerTeam)) {
            throw new ForbiddenException(who.name() + " may not change " + what + " (owned by " + ownerTeam + ")");
        }
    }

    public static void requireApprove(Identity who, String group, String what) {
        if (!canApprove(who, group)) {
            throw new ForbiddenException(who.name() + " is not in approver group " + group + " for " + what);
        }
    }

    /** Refuses with the credential's reference and its owner team, and nothing else about it. */
    public static void requireUseCredential(Identity who, String credential, String owner) {
        if (!canUseCredential(who, owner)) {
            throw new ForbiddenException(who.name() + " may not use credential " + credential + " (owned by " + owner + ")");
        }
    }
}
