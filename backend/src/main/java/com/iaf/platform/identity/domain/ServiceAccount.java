package com.iaf.platform.identity.domain;

import java.time.Instant;
import java.util.List;

import com.iaf.platform.common.domain.Identity;

/**
 * A non-human caller (automation, an agent). The token hash is stored, never the token.
 * {@code scopes} limit what it may call ({@link Scopes}); an account stored without scopes has
 * {@link Scopes#DEFAULT}, and the {@code admin} scope makes it an admin.
 */
public record ServiceAccount(String id, String name, List<String> roles, List<String> teams, String tokenHash, Instant createdAt, List<String> scopes) {

    public ServiceAccount(String id, String name, List<String> roles, List<String> teams, String tokenHash, Instant createdAt) {
        this(id, name, roles, teams, tokenHash, createdAt, null);
    }

    /** The scopes it holds: its own, or the defaults when it was stored without any. */
    public List<String> grantedScopes() {
        return scopes == null || scopes.isEmpty() ? Scopes.DEFAULT : scopes;
    }

    public Identity identity() {
        return new Identity(id, name, null, teams, roles, grantedScopes().contains(Scopes.ADMIN), Identity.Kind.SERVICE);
    }

    /** The account as the API shows it: no token hash. */
    public ServiceAccount withoutSecret() {
        return new ServiceAccount(id, name, roles, teams, null, createdAt, grantedScopes());
    }
}
