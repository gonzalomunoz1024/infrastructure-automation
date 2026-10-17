package com.iaf.platform.common.ports;

import java.time.Instant;
import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Secrets. Workflow and provider files only ever hold references ({@code vault:<path>}); the
 * platform resolves them at call time and never returns a value through the API.
 */
public interface CredentialsPort {

    record CredentialInfo(String name, String type, String path, Instant updatedAt) {
    }

    /** The secret behind a reference like {@code vault:vsphere/token}; empty Mono when unknown. */
    Mono<String> resolve(String reference);

    Mono<List<CredentialInfo>> list();

    /** {@code type} is token, password, ssh-key, api-key, basic or file. */
    Mono<CredentialInfo> store(String name, String type, String value, String by);

    static String referenceOf(String name) {
        return name == null ? null : (name.startsWith("vault:") ? name : "vault:" + name);
    }
}
