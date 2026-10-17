package com.iaf.platform.identity.ports.inbound;

import java.util.List;

import reactor.core.publisher.Mono;

public interface IdentityCommands {

    record CreateServiceAccount(String name, List<String> roles, List<String> teams) {
    }

    /** The token is returned once and never stored. */
    record ServiceAccountCreated(String id, String token) {
    }

    Mono<ServiceAccountCreated> createServiceAccount(CreateServiceAccount command);
}
