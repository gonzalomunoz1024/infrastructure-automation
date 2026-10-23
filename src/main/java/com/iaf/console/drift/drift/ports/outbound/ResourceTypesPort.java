package com.iaf.console.drift.drift.ports.outbound;

import com.iaf.console.drift.drift.domain.TypeInfo;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The resource types the platform publishes (P-06), as the drift service needs them. */
@GlobalRead("resource types are content, the same for every account")
public interface ResourceTypesPort {

    Mono<TypeInfo> find(String name);

    Flux<TypeInfo> list();
}
