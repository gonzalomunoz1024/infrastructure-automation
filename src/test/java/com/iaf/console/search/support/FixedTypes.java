package com.iaf.console.search.support;

import java.util.Map;

import com.iaf.console.search.index.domain.Fixtures;
import com.iaf.console.search.index.domain.ResourceTypeHints;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The two demo types and their hints, without a platform. */
public final class FixedTypes implements ResourceTypesPort {

    private final Map<String, ResourceTypeHints> types;

    public FixedTypes() {
        this(Map.of("vm", Fixtures.VM_HINTS, "database", Fixtures.DB_HINTS));
    }

    public FixedTypes(Map<String, ResourceTypeHints> types) {
        this.types = Map.copyOf(types);
    }

    @Override
    public Mono<ResourceTypeHints> find(String name) {
        return Mono.justOrEmpty(types.get(name));
    }

    @Override
    public Flux<ResourceTypeHints> list() {
        return Flux.fromIterable(types.values());
    }
}
