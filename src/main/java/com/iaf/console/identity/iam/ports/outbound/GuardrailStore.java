package com.iaf.console.identity.iam.ports.outbound;

import com.iaf.console.identity.iam.domain.Guardrail;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Where the organization's guardrails live: they belong to no account, so every read is global; names are unique. */
@GlobalRead
public interface GuardrailStore {

    Mono<Guardrail> insert(Guardrail guardrail);

    /** Replaces the guardrail when its stored version is {@code expectedVersion}; empty otherwise. */
    Mono<Guardrail> update(Guardrail guardrail, long expectedVersion);

    Mono<Boolean> delete(String guardrailId);

    Mono<Guardrail> findById(String guardrailId);

    /** Every guardrail, in id order. */
    Flux<Guardrail> findAll();
}
