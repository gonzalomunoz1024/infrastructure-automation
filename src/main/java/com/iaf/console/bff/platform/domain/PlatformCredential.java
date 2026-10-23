package com.iaf.console.bff.platform.domain;

import java.util.Objects;

/** The platform service account the console acts through for an account (P-03), and its bearer token. */
public record PlatformCredential(String accountId, String serviceAccountId, String token) {

    public PlatformCredential {
        Objects.requireNonNull(token, "token");
        if (token.isBlank()) {
            throw new IllegalArgumentException("a platform credential needs a token");
        }
    }

    @Override
    public String toString() {
        return "PlatformCredential[accountId=" + accountId + ", serviceAccountId=" + serviceAccountId + ", token=***]";
    }
}
