package com.iaf.console.bff.session.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What a sign-in in flight remembers between the redirect to the identity provider and its
 * callback: the OIDC state, the PKCE verifier, the nonce, where to go afterwards, and, for a
 * step-up, the session being re-authenticated. Sealed into a short-lived cookie, so a callback may
 * land on any replica.
 */
public record LoginTicket(String state, String codeVerifier, String nonce, String returnTo, String sessionId, boolean stepUp, Instant expiresAt) {

    public LoginTicket {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(codeVerifier, "codeVerifier");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(expiresAt, "expiresAt");
        returnTo = ReturnTo.sanitize(returnTo);
    }

    public boolean expired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
