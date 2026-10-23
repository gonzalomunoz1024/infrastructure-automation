package com.iaf.console.sdk;

import java.time.Instant;
import java.util.Objects;

/**
 * An access token and the refresh token that renews it, as {@code POST /auth/token} answers them.
 *
 * @param accessToken the bearer token, a JWT that lives 15 minutes
 * @param tokenType {@code Bearer}
 * @param refreshToken renews the access token; it rotates on every refresh, and a rotated one
 *     presented again ends the whole API session (null when there is none)
 * @param expiresAt when the access token expires (null when unknown)
 * @param refreshExpiresAt when the refresh token expires (null when unknown)
 * @param scope {@code console}
 * @param clientId the client the tokens were issued to, which a refresh must name
 */
public record TokenSet(
        String accessToken,
        String tokenType,
        String refreshToken,
        Instant expiresAt,
        Instant refreshExpiresAt,
        String scope,
        String clientId) {

    public TokenSet {
        Objects.requireNonNull(accessToken, "accessToken");
        if (tokenType == null) tokenType = "Bearer";
    }

    /** An access token with its refresh token, issued to {@code console-sdk}. */
    public static TokenSet of(String accessToken, String refreshToken, Instant expiresAt) {
        return new TokenSet(accessToken, "Bearer", refreshToken, expiresAt, null, null, null);
    }

    /** The tokens stay out of logs. */
    @Override
    public String toString() {
        return "TokenSet[tokenType=" + tokenType
                + ", refreshToken=" + (refreshToken == null ? "none" : "present")
                + ", expiresAt=" + expiresAt
                + ", refreshExpiresAt=" + refreshExpiresAt
                + ", scope=" + scope
                + ", clientId=" + clientId + "]";
    }
}
