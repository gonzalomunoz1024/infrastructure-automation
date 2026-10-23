package com.iaf.console.identity.authz.application;

import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;
import com.iaf.console.identity.events.IdentityChange;
import com.iaf.console.identity.events.IdentityChangeListener;

import reactor.core.publisher.Mono;

/** Grows the account's authorization version on every identity change. Runs before the snapshot listener. */
public final class AuthzVersions implements IdentityChangeListener {

    private final AuthzVersionPort versions;

    public AuthzVersions(AuthzVersionPort versions) {
        this.versions = versions;
    }

    @Override
    public Mono<Void> changed(IdentityChange change) {
        return versions.bump(change.accountId()).then();
    }
}
