package com.iaf.console.identity.authz.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The answer to one check. On an explicit decision, the policy, statement and role that decided;
 * on a default deny, whether a managed role would allow it and which.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Decision(boolean allowed, String reason, String policyId, String statement, String role, Boolean requestable, String suggestedRole) {

    public static Decision allow(String reason) {
        return new Decision(true, reason, null, null, null, null, null);
    }

    public static Decision deny(String reason) {
        return new Decision(false, reason, null, null, null, null, null);
    }
}
