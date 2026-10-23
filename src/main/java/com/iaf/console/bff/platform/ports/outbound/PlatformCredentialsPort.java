package com.iaf.console.bff.platform.ports.outbound;

import com.iaf.console.bff.platform.domain.PlatformCredential;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** Where an account's platform credential comes from: configuration, the demo seed, or the identity service's vault answer. */
public interface PlatformCredentialsPort {

    /** The credential of the scope's account; errors when the account has none. */
    Mono<PlatformCredential> findCredential(AccountScope scope);

    /** The console-internal credential the BFF reads the platform's feed and content with. */
    @com.iaf.console.kit.scope.GlobalRead("the internal credential belongs to the console itself")
    Mono<PlatformCredential> findInternalCredential();
}
