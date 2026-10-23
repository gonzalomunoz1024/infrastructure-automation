package com.iaf.console.identity.iam.adapters.memory;

import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListMap;

import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.identity.iam.ports.outbound.GuardrailStore;
import com.iaf.console.kit.errors.ConflictException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The in-memory guardrails of the demo and the tests. */
public final class InMemoryGuardrailStore implements GuardrailStore {

    private final ConcurrentSkipListMap<String, Guardrail> byId = new ConcurrentSkipListMap<>();

    @Override
    public synchronized Mono<Guardrail> insert(Guardrail guardrail) {
        if (byId.containsKey(guardrail.guardrailId()) || nameTaken(guardrail)) {
            return Mono.error(new ConflictException("a guardrail named '" + guardrail.name() + "' exists"));
        }
        byId.put(guardrail.guardrailId(), guardrail);
        return Mono.just(guardrail);
    }

    @Override
    public synchronized Mono<Guardrail> update(Guardrail guardrail, long expectedVersion) {
        Guardrail current = byId.get(guardrail.guardrailId());
        if (current == null || current.version() != expectedVersion) {
            return Mono.empty();
        }
        if (nameTaken(guardrail)) {
            return Mono.error(new ConflictException("a guardrail named '" + guardrail.name() + "' exists"));
        }
        byId.put(guardrail.guardrailId(), guardrail);
        return Mono.just(guardrail);
    }

    @Override
    public Mono<Boolean> delete(String guardrailId) {
        return Mono.fromSupplier(() -> byId.remove(guardrailId) != null);
    }

    @Override
    public Mono<Guardrail> findById(String guardrailId) {
        return Mono.justOrEmpty(byId.get(guardrailId));
    }

    @Override
    public Flux<Guardrail> findAll() {
        return Flux.fromIterable(byId.values()).sort(Comparator.comparing(Guardrail::guardrailId));
    }

    private boolean nameTaken(Guardrail guardrail) {
        return byId.values().stream().anyMatch(g -> g.name().equals(guardrail.name()) && !g.guardrailId().equals(guardrail.guardrailId()));
    }
}
