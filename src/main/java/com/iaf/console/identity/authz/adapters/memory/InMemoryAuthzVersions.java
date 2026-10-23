package com.iaf.console.identity.authz.adapters.memory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.identity.authz.ports.outbound.AuthzVersionPort;

import reactor.core.publisher.Mono;

/** The in-memory versions of the demo and the tests. */
public final class InMemoryAuthzVersions implements AuthzVersionPort {

    private final Map<String, AtomicLong> perAccount = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong();

    @Override
    public Mono<Long> bump(String accountId) {
        return Mono.fromSupplier(() -> {
            revision.incrementAndGet();
            return perAccount.computeIfAbsent(accountId, k -> new AtomicLong()).incrementAndGet();
        });
    }

    @Override
    public Mono<Long> getRevision() {
        return Mono.fromSupplier(revision::get);
    }

    @Override
    public Mono<Long> getVersion(String accountId) {
        return Mono.fromSupplier(() -> {
            AtomicLong v = perAccount.get(accountId);
            return v == null ? 0L : v.get();
        });
    }
}
