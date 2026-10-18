package com.iaf.platform.providers.ports.inbound;

import com.iaf.platform.common.domain.CommandAccepted;

import reactor.core.publisher.Mono;

/** Storing a secret through the {@code CredentialsPort}; the value never comes back through the API. */
public interface CredentialsCommands {

    /**
     * {@code type} is token, password, ssh-key, api-key, basic or file; {@code name} may carry the
     * {@code vault:} prefix. {@code owner} is the team that may use the credential: one of the
     * caller's teams (their first when left out; a new value keeps an existing owner), any team
     * for an admin.
     */
    record StoreCredential(String name, String type, String value, String owner) {
        public StoreCredential(String name, String type, String value) {
            this(name, type, value, null);
        }
    }

    Mono<CommandAccepted> storeCredential(StoreCredential command);
}
