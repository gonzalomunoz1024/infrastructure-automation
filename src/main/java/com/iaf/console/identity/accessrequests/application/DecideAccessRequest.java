package com.iaf.console.identity.accessrequests.application;

/** The body of {@code POST /v1/accounts/{accountId}/access-requests/{requestId}:decide}. */
public record DecideAccessRequest(String decision, String comment) {
}
