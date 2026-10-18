package com.iaf.platform.resources.domain;

import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;

/**
 * Who may change a registry entry. A resource is its owner team's: the team may retire it or
 * update it through a register step, as may an admin (the platform itself is one); a resource
 * without an owner is anyone's. The job that created a resource may also update it, whoever
 * runs the job, so a workflow that registers {@code building} and later {@code ready} finishes
 * its own work. Pure, so tests judge it without Reactor.
 */
public final class ResourceRules {

    private ResourceRules() {
    }

    /** The owner team, an admin, or anyone when the resource has no owner. */
    public static boolean canChange(Identity who, Resource resource) {
        if (who == null || resource == null) {
            return false;
        }
        if (who.admin()) {
            return true;
        }
        String owner = resource.owner();
        if (owner == null || owner.isBlank()) {
            return true;
        }
        return who.teams() != null && who.teams().contains(owner);
    }

    /** {@link #canChange}, or the job that created the resource ({@code jobId}) updating it. */
    public static boolean canUpdate(Identity who, Resource resource, String jobId) {
        if (canChange(who, resource)) {
            return true;
        }
        return jobId != null && !jobId.isBlank() && resource.createdBy() != null && jobId.equals(resource.createdBy().jobId());
    }

    public static void requireChange(Identity who, Resource resource, String what) {
        if (!canChange(who, resource)) {
            throw new ForbiddenException(refusal(who, resource, what));
        }
    }

    public static void requireUpdate(Identity who, Resource resource, String jobId, String what) {
        if (!canUpdate(who, resource, jobId)) {
            throw new ForbiddenException(refusal(who, resource, what));
        }
    }

    private static String refusal(Identity who, Resource resource, String what) {
        return (who == null ? "nobody" : who.name()) + " may not " + what + " " + resource.type() + " " + resource.id()
                + " (owned by " + resource.owner() + ")";
    }
}
