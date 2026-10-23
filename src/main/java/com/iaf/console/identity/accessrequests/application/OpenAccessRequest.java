package com.iaf.console.identity.accessrequests.application;

/** The body of {@code POST /v1/accounts/{accountId}/access-requests}. */
public record OpenAccessRequest(String action, String resource, String role, String reason) {
}
