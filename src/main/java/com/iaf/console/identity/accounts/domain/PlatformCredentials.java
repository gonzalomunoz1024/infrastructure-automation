package com.iaf.console.identity.accounts.domain;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What the console's own services get from {@code GET /v1/accounts/{accountId}/platform-credentials}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlatformCredentials(String accountId, String platformServiceAccountId, String source, String token, String vaultPath, Instant issuedAt) {
}
