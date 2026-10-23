package com.iaf.console.identity.authz.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * The authorization versions: one per account, grown by one on every identity change of the
 * account, and the global revision every change grows, the bundle's revision.
 */
public interface AuthzVersionPort {

    /** Grows the account's version and the global revision; answers the account's new version. */
    Mono<Long> bump(String accountId);

    @GlobalRead
    Mono<Long> getRevision();

    @GlobalRead
    Mono<Long> getVersion(String accountId);
}
