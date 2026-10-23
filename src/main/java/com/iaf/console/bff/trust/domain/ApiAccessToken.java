package com.iaf.console.bff.trust.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What an access token of the console API says (decision 31, RFC 9068): the person's subject at
 * the identity provider ({@code sub}), the API session it belongs to ({@code sid}), the client it
 * was issued to ({@code client_id}), when the browser sign-in that approved it happened
 * ({@code auth_time}, which the step-up reads), when it was issued and until when it holds, and its
 * own id ({@code jti}).
 */
public record ApiAccessToken(String subject, String sessionId, String clientId, Instant authTime, Instant issuedAt, Instant expiresAt, String tokenId) {

    public ApiAccessToken {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(tokenId, "tokenId");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("an access token expires after it is issued");
        }
    }
}
