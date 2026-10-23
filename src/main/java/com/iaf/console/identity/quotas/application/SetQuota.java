package com.iaf.console.identity.quotas.application;

/** The body of {@code PUT /v1/accounts/{accountId}/quotas/{quotaKey}}. */
public record SetQuota(Integer limit, Integer burst) {
}
