package com.iaf.console.identity.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Why a call was refused, as the console shows it: the layer that decided, the action and resource
 * checked, the reason, the role and statement that decided (on an explicit deny), and, on a
 * default deny, whether a managed role would allow it and which (so the console can offer to
 * request access). Within console IAM, {@code scope} says which part refused it ({@code policy},
 * a role's permission {@code boundary}, or an organization {@code guardrail}, by
 * {@code guardrailId}); requesting a role does not help past a boundary or a guardrail.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Denial(String layer, String action, String resource, String reason, String role, String policyId, String statement,
        Boolean requestable, String suggestedRole, String scope, String guardrailId) {

    public Denial(String layer, String action, String resource, String reason, String role, String policyId, String statement, Boolean requestable,
            String suggestedRole) {
        this(layer, action, resource, reason, role, policyId, statement, requestable, suggestedRole, null, null);
    }

    /** The layer name of this service's own IAM checks. */
    public static final String CONSOLE_IAM = "console-iam";
}
