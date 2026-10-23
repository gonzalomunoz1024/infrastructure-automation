package com.iaf.console.identity.quotas.application;

/** The body of {@code POST /v1/accounts/{accountId}/quota-requests/{requestId}:decide}: on approve, {@code limit} is the limit granted (the ask by default). */
public record QuotaDecision(String decision, Integer limit, String comment) {
}
