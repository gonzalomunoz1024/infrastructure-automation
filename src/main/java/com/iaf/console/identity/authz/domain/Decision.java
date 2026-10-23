package com.iaf.console.identity.authz.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer to one check. On an explicit decision, what decided it: {@code scope} is
 * {@code policy} (a statement of a role's policy, through the role), {@code boundary} (the
 * permission boundary of the role that would have allowed it) or {@code guardrail} (one of the
 * organization's guardrails, by id); {@code elevated} says the deciding role is held for a while,
 * not through a group. On a default deny, whether a managed role would allow it and which.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Decision(boolean allowed, String reason, String policyId, String statement, String role, Boolean requestable, String suggestedRole,
        String scope, String guardrailId, Boolean elevated) {

    public static final String POLICY = "policy";
    public static final String BOUNDARY = "boundary";
    public static final String GUARDRAIL = "guardrail";

    public Decision(boolean allowed, String reason, String policyId, String statement, String role, Boolean requestable, String suggestedRole) {
        this(allowed, reason, policyId, statement, role, requestable, suggestedRole, null, null, null);
    }

    public static Decision allow(String reason) {
        return new Decision(true, reason, null, null, null, null, null);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason, null, null, null, null, null);
    }
}
