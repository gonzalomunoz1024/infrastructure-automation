package com.iaf.platform.identity.ports.outbound;

import java.util.Optional;

import com.iaf.platform.common.domain.Identity;

/** Turns a bearer token from an identity provider (OIDC) into an identity; empty when invalid. */
public interface TokenVerifier {
    Optional<Identity> verify(String token);
}
