package com.iaf.console.bff.access.domain;

import java.util.Objects;

/**
 * Why an action was refused, naming the layer that decided: the account's own policies
 * ({@code console-iam}, with the role, policy and statement) or the platform's RBAC on the
 * account's service account ({@code platform}, with the missing scope). {@code requestable} says
 * whether Request access is offered, and {@code suggestedRole} which managed role would allow it.
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
        boolean requestable,
        String suggestedRole) {

    public static final String CONSOLE_IAM = "console-iam";
    public static final String PLATFORM = "platform";

    public Denial {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(action, "action");
        reason = reason == null || reason.isBlank() ? "the action is not allowed" : reason;
    }

    /** A refusal by the account's policies. */
    public static Denial byPolicy(String action, String resource, String reason, String role, String policyId, String statement) {
        return new Denial(CONSOLE_IAM, action, resource, reason, role, policyId, statement, null, true, ManagedRoles.suggestedFor(action));
    }

    /** A refusal by the platform for the account's service account. */
    public static Denial byPlatform(String action, String resource, String reason, String scope) {
        return new Denial(PLATFORM, action, resource, reason, null, null, null, scope, false, null);
    }
}
