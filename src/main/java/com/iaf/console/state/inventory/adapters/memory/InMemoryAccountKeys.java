package com.iaf.console.state.inventory.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The wrapped data keys in memory: the demo's secrets live as long as the process. */
public final class InMemoryAccountKeys implements AccountKeysPort {

    private final Map<String, WrappedKey> keys = new ConcurrentHashMap<>();

    @Override
    public Mono<WrappedKey> find(String accountId) {
        return Mono.justOrEmpty(keys.get(accountId));
    }

    @Override
    public Mono<WrappedKey> save(WrappedKey key) {
        return Mono.fromSupplier(() -> {
            WrappedKey existing = keys.putIfAbsent(key.accountId(), key);
            return existing == null ? key : existing;
        });
    }

    @Override
    public Mono<Boolean> destroy(String accountId) {
        return Mono.fromSupplier(() -> keys.remove(accountId) != null);
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.fromIterable(keys.keySet());
    }
}
