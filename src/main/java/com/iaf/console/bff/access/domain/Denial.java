package com.iaf.console.bff.access.domain;

import java.util.Objects;

/**
 * Why an action was refused, naming the layer that decided: the account's own policies
 * ({@code console-iam}, with the role, policy and statement) or the platform's RBAC on the
 * account's service account ({@code platform}, with the missing scope). {@code requestable} says
 * whether Request access is offered, and {@code suggestedRole} which managed role would allow it.
 * A console-iam refusal also says which part of console IAM refused it ({@code refusedBy}: a
 * role's {@code policy}, the role's permissions {@code boundary}, or an organization
 * {@code guardrail}, named by {@code guardrailId}); asking for a role does not help past a
 * boundary or a guardrail, so those are never requestable.
 */
public record Denial(
        String layer,
        String action,
        String resource,
        String reason,
        String role,
        String policyId,
        String statement,
        String scope,
        String refusedBy,
        String guardrailId,
        boolean requestable,
        String suggestedRole) {

    public static final String CONSOLE_IAM = "console-iam";
    public static final String PLATFORM = "platform";

    /** A statement of one of the person's roles refused it. */
    public static final String BY_POLICY = "policy";
    /** The permissions boundary of the role that would have allowed it refused it. */
    public static final String BY_BOUNDARY = "boundary";
    /** One of the organization's guardrails refused it. */
    public static final String BY_GUARDRAIL = "guardrail";

    public Denial {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(action, "action");
        reason = reason == null || reason.isBlank() ? "the action is not allowed" : reason;
        refusedBy = refusedBy == null || refusedBy.isBlank() ? null : refusedBy;
        if (beyondRoles(refusedBy)) {
            requestable = false;
        }
    }

    /** A denial without the console-iam part that refused it. */
    public Denial(String layer, String action, String resource, String reason, String role, String policyId, String statement, String scope,
            boolean requestable, String suggestedRole) {
        this(layer, action, resource, reason, role, policyId, statement, scope, null, null, requestable, suggestedRole);
    }

    /** A refusal by the account's policies. */
    public static Denial byPolicy(String action, String resource, String reason, String role, String policyId, String statement) {
        return new Denial(CONSOLE_IAM, action, resource, reason, role, policyId, statement, null, true, ManagedRoles.suggestedFor(action));
    }

    /**
     * A refusal as the identity service decided it: {@code refusedBy} is its decision's
     * {@code scope} (policy, boundary or guardrail); {@code requestable} and {@code suggestedRole}
     * are its own when it sent them. A boundary or a guardrail is never requestable and suggests
     * no role.
     */
    public static Denial decided(String action, String resource, String reason, String role, String policyId, String statement, String refusedBy,
            String guardrailId, Boolean requestable, String suggestedRole) {
        String by = isRefusedBy(refusedBy) ? refusedBy : null;
        boolean beyond = beyondRoles(by);
        boolean canAsk = !beyond && (requestable == null || requestable);
        String suggested = beyond ? null : suggestedRole == null || suggestedRole.isBlank() ? ManagedRoles.suggestedFor(action) : suggestedRole;
        return new Denial(CONSOLE_IAM, action, resource, reason, role, policyId, statement, null, by, guardrailId, canAsk, suggested);
    }

    /** A refusal by the platform for the account's service account. */
    public static Denial byPlatform(String action, String resource, String reason, String scope) {
        return new Denial(PLATFORM, action, resource, reason, null, null, null, scope, false, null);
    }

    /** The same denial naming another role. */
    public Denial withRole(String otherRole) {
        return new Denial(layer, action, resource, reason, otherRole, policyId, statement, scope, refusedBy, guardrailId, requestable, suggestedRole);
    }

    /** Whether a value is one of the identity service's names for what refused an action. */
    public static boolean isRefusedBy(String value) {
        return BY_POLICY.equals(value) || BY_BOUNDARY.equals(value) || BY_GUARDRAIL.equals(value);
    }

    private static boolean beyondRoles(String refusedBy) {
        return BY_BOUNDARY.equals(refusedBy) || BY_GUARDRAIL.equals(refusedBy);
    }
}
