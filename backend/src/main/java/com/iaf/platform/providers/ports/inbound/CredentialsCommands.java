package com.iaf.platform.providers.ports.inbound;

import com.iaf.platform.common.domain.CommandAccepted;

import reactor.core.publisher.Mono;

/** Storing a secret through the {@code CredentialsPort}; the value never comes back through the API. */
public interface CredentialsCommands {

    /** {@code type} is token, password, ssh-key, api-key, basic or file; {@code name} may carry the {@code vault:} prefix. */
    record StoreCredential(String name, String type, String value) {
    }

    Mono<CommandAccepted> storeCredential(StoreCredential command);
}
