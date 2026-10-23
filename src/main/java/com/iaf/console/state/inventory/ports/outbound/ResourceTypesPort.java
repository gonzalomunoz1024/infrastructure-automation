package com.iaf.console.state.inventory.ports.outbound;

import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.state.inventory.domain.ResourceTypeInfo;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The resource types the platform publishes (P-06), as the inventory needs them. */
@GlobalRead("resource types are content, the same for every account")
public interface ResourceTypesPort {

    /** The type of a name; empty when the platform has none by it. */
    Mono<ResourceTypeInfo> find(String name);

    Flux<ResourceTypeInfo> list();
}
