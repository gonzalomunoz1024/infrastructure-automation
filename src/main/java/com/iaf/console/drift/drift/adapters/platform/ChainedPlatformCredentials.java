package com.iaf.console.drift.drift.adapters.platform;

import java.util.List;
import java.util.Map;

import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The first credential source that knows the account: the configured map, then the demo seed, then the identity service. */
public final class ChainedPlatformCredentials implements PlatformCredentialsPort {

    private final Map<String, String> configured;
    private final List<PlatformCredentialsPort> next;

    public ChainedPlatformCredentials(Map<String, String> configured, List<PlatformCredentialsPort> next) {
        this.configured = Map.copyOf(configured);
        this.next = List.copyOf(next);
    }

    @Override
    public Mono<String> tokenFor(String accountId) {
        String token = configured.get(accountId);
        if (token != null && !token.isBlank()) {
            return Mono.just(token.trim());
        }
        return Flux.fromIterable(next).concatMap(source -> source.tokenFor(accountId)).next();
    }
}
