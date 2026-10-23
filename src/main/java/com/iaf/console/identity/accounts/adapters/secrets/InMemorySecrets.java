package com.iaf.console.identity.accounts.adapters.secrets;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.identity.accounts.domain.PlatformSecret;
import com.iaf.console.identity.accounts.ports.outbound.SecretsPort;

import reactor.core.publisher.Mono;

/** The {@code local} secrets store of the demo and the tests: this process's memory, gone at exit. */
public final class InMemorySecrets implements SecretsPort {

    private final Map<String, PlatformSecret> secrets = new ConcurrentHashMap<>();

    @Override
    public String source() {
        return "local";
    }

    @Override
    public String vaultPath(String accountId) {
        return null;
    }

    @Override
    public Mono<Void> put(PlatformSecret secret) {
        return Mono.fromRunnable(() -> secrets.put(secret.accountId(), secret));
    }

    @Override
    public Mono<PlatformSecret> find(String accountId) {
        return Mono.justOrEmpty(secrets.get(accountId));
    }

    @Override
    public Mono<Void> delete(String accountId) {
        return Mono.fromRunnable(() -> secrets.remove(accountId));
    }
}
