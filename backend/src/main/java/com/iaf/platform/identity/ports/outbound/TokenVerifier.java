package com.iaf.platform.identity.ports.outbound;

import com.iaf.platform.common.domain.Identity;

import reactor.core.publisher.Mono;

/**
 * Turns a bearer token from an identity provider (OIDC) into an identity; empty when invalid.
 * Verifying may fetch the provider's keys, so it answers asynchronously and never on the caller's
 * thread when that would block.
 */
public interface TokenVerifier {
    Mono<Identity> verify(String token);
}
