package com.iaf.platform.resources.ports.inbound;

import java.util.List;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Read side of the registry, used by the interpreter's {@code use} step, the generated read endpoints and the previews. */
public interface ResourceQueries {

    Flux<ResourceTypeDefinition> types();

    Mono<ResourceTypeDefinition> type(String name);

    Flux<Resource> list(String type, String owner, String status);

    /** Empty Mono when unknown. */
    Mono<Resource> get(String id);

    /**
     * {@code use}: the resource, after an access check for the requester; a
     * {@link com.iaf.platform.common.domain.ForbiddenException} when policy denies it.
     */
    Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess);

    record Lineage(Resource resource, List<Resource> builtFrom, List<Resource> dependents) {
    }

    Mono<Lineage> lineage(String id);
}
