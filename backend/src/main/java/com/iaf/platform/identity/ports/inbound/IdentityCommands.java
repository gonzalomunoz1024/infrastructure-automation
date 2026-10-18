package com.iaf.platform.identity.ports.inbound;

import java.util.List;

import reactor.core.publisher.Mono;

public interface IdentityCommands {

    /**
     * {@code scopes} limit what the account may call (see identity.domain.Scopes); {@code preset}
     * names a set instead ({@code assistant}, {@code invoker}, {@code ci}). Neither: the defaults.
     * {@code teams} are the teams it acts for; none given, it acts for its creator's.
     */
    record CreateServiceAccount(String name, List<String> roles, List<String> teams, List<String> scopes, String preset) {
    }

    /**
     * The token is returned once and never stored. {@code teams} are the teams the account acts
     * for; {@code note} says where they came from when none were given (the creator's teams, or
     * none at all, which the policy refuses every job for).
     */
    record ServiceAccountCreated(String id, String token, List<String> scopes, List<String> teams, String note) {
    }

    Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount command);
}
