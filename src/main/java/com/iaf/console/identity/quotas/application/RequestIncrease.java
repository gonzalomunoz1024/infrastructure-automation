package com.iaf.console.identity.quotas.application;

/** The body of {@code POST /v1/accounts/{accountId}/quotas/{quotaKey}:request-increase}. */
public record RequestIncrease(Integer limit, String reason) {
}
