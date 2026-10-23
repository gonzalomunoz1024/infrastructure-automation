package com.iaf.console.identity.accounts.ports.outbound;

import com.iaf.console.identity.accounts.domain.PlatformSecret;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/**
 * Where a platform service account's token lives: this process (the demo and the tests) or Vault.
 * The token is never in MongoDB, an event or a log.
 */
public interface SecretsPort {

    /** {@code local} or {@code vault}, as the credentials answer names it. */
    String source();

    /** The Vault path of an account's secret; null for the local store. */
    String vaultPath(String accountId);

    Mono<Void> put(PlatformSecret secret);

    @GlobalRead
    Mono<PlatformSecret> find(String accountId);

    Mono<Void> delete(String accountId);
}
