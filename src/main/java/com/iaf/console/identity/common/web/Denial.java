package com.iaf.console.identity.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Why a call was refused, as the console shows it: the layer that decided, the action and resource
 * checked, the reason, the role and statement that decided (on an explicit deny), and, on a
 * default deny, whether a managed role would allow it and which (so the console can offer to
 * request access).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Denial(String layer, String action, String resource, String reason, String role, String policyId, String statement,
        Boolean requestable, String suggestedRole) {

    /** The layer name of this service's own IAM checks. */
    public static final String CONSOLE_IAM = "console-iam";
}
