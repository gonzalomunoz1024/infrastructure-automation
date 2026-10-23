package com.iaf.console.state.inventory.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.state.inventory.ports.outbound.AccountKeysPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The wrapped data keys in memory: the demo's secrets live as long as the process. */
public final class InMemoryAccountKeys implements AccountKeysPort {

    private final Map<String, WrappedKey> keys = new ConcurrentHashMap<>();

    private final java.util.Set<String> shredded = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Mono<WrappedKey> find(String accountId) {
        return Mono.justOrEmpty(keys.get(accountId));
    }

    @Override
    public synchronized Mono<WrappedKey> save(WrappedKey key) {
        if (shredded.contains(key.accountId())) {
            return Mono.empty();
        }
        WrappedKey existing = keys.putIfAbsent(key.accountId(), key);
        return Mono.just(existing == null ? key : existing);
    }

    @Override
    public synchronized Mono<Boolean> destroy(String accountId) {
        boolean had = keys.remove(accountId) != null;
        shredded.add(accountId);
        return Mono.just(had);
    }

    @Override
    public Flux<String> listAccounts() {
        return Flux.fromIterable(keys.keySet());
    }
}
