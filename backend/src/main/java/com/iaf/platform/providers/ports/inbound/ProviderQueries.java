package com.iaf.platform.providers.ports.inbound;

import com.iaf.platform.providers.domain.ProviderDefinition;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read side of the providers slice, used by the checks, the interpreter and the registry. */
public interface ProviderQueries {

    /** Empty Mono when unknown. */
    Mono<ProviderDefinition> provider(String name);

    Flux<ProviderDefinition> all();
}
