package com.iaf.console.drift.drift.ports.outbound;

import reactor.core.publisher.Mono;

/** Where an account's platform token comes from: the identity service, the configuration, or the demo's seed. */
public interface PlatformCredentialsPort {

    /** The bearer token of the account's platform service account; empty when the account has none. */
    Mono<String> tokenFor(String accountId);
}
