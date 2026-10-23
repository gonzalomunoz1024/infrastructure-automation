package com.iaf.console.search.index.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.search.index.domain.ResourceTypeHints;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The resource types the platform publishes (P-06), for what each one lets the index keep. */
@GlobalRead("resource types are the platform's catalog, the same for every account")
public interface ResourceTypesPort {

    /** The hints of a type, empty when the platform does not publish it. */
    Mono<ResourceTypeHints> find(String name);

    Flux<ResourceTypeHints> list();
}
