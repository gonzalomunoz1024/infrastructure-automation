package com.iaf.platform.common.ports;

import java.time.Instant;
import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Secrets. Workflow and provider files only ever hold references ({@code vault:<path>}); the
 * platform resolves them at call time and never returns a value through the API. A credential
 * belongs to a team: its {@code owner}, the only team that may send it outward from the provider
 * wizard and the imports (admins may use any). One without an owner (seeded from the content
 * files, or kept in the vault before owners existed) may be used by anyone.
 */
public interface CredentialsPort {

    /** A credential without its value; {@code owner} is null when anyone may use it. */
    record CredentialInfo(String name, String type, String path, String owner, Instant updatedAt) {
    }

    /** The secret behind a reference like {@code vault:vsphere/token}; empty Mono when unknown. */
    Mono<String> resolve(String reference);

    Mono<List<CredentialInfo>> list();

    /** The credential a reference (or a bare name) names, without its value; empty Mono when unknown. */
    default Mono<CredentialInfo> info(String reference) {
        String name = nameOf(reference);
        if (name == null || name.isBlank()) {
            return Mono.empty();
        }
        return list().flatMap(all -> Mono.justOrEmpty(all.stream().filter(c -> name.equals(c.name())).findFirst()));
    }

    /**
     * {@code type} is token, password, ssh-key, api-key, basic or file; {@code owner} is the team
     * that may use the credential (null: anyone). Upserts, owner included.
     */
    Mono<CredentialInfo> store(String name, String type, String value, String by, String owner);

    static String referenceOf(String name) {
        return name == null ? null : (name.startsWith("vault:") ? name : "vault:" + name);
    }

    /** The name behind a reference: {@code vault:vsphere/token} is {@code vsphere/token}; a bare name stays as it is. */
    static String nameOf(String reference) {
        if (reference == null) {
            return null;
        }
        String r = reference.trim();
        return r.startsWith("vault:") ? r.substring("vault:".length()) : r;
    }
}
