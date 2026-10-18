package com.iaf.platform.identity.ports.inbound;

import java.util.List;

import reactor.core.publisher.Mono;

public interface IdentityCommands {

    /**
     * {@code scopes} limit what the account may call (see identity.domain.Scopes); {@code preset}
     * names a set instead ({@code assistant}, {@code invoker}, {@code ci}). Neither: the defaults.
     */
    record CreateServiceAccount(String name, List<String> roles, List<String> teams, List<String> scopes, String preset) {

        public CreateServiceAccount(String name, List<String> roles, List<String> teams) {
            this(name, roles, teams, null, null);
        }
    }

    /** The token is returned once and never stored. */
    record ServiceAccountCreated(String id, String token, List<String> scopes) {

        public ServiceAccountCreated(String id, String token) {
            this(id, token, null);
        }
    }

    Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount command);
}
