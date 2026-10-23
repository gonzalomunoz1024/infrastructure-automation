package com.iaf.console.bff.session.ports.outbound;

import com.iaf.console.bff.session.domain.IdpUser;

import reactor.core.publisher.Mono;

/** The OIDC provider: where to send the browser, and how to turn its code into a verified user. */
public interface IdentityProviderPort {

    /** The authorization URL for one sign-in (authorization code with PKCE S256). */
    Mono<String> authorizationUrl(AuthorizationRequest request);

    /** Exchanges the code for tokens and verifies the ID token, its nonce included. */
    Mono<IdpUser> exchange(String code, String codeVerifier, String expectedNonce);

    /** The provider's end-session URL for the user, when it has one; empty otherwise. */
    Mono<String> endSessionUrl(String postLogoutReturnTo);

    /** What a sign-in asks for: the state, nonce and PKCE challenge, a login hint, and whether a fresh authentication is required. */
    record AuthorizationRequest(String state, String nonce, String codeChallenge, String loginHint, boolean freshAuthentication) {
    }
}
