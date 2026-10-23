package com.iaf.console.identity.accounts.domain;

import java.time.Instant;

/** The platform service account bound to an account and its bearer token; lives in the secrets store only. */
public record PlatformSecret(String accountId, String serviceAccountId, String token, Instant issuedAt) {

    @Override
    public String toString() {
        return "PlatformSecret[" + accountId + ", " + serviceAccountId + "]";
    }
}
