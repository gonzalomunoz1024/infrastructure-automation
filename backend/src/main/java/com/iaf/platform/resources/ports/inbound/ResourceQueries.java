package com.iaf.platform.resources.ports.inbound;

import java.time.Duration;
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

    /**
     * {@code use} with {@code waitUntil}: the same, but it waits ({@link #awaitState}) until the
     * resource reaches that state ({@code ready}); a {@link com.iaf.platform.common.domain.ConflictException}
     * ("resource not ready") when it does not within the wait window. Null means no wait.
     */
    Mono<Resource> use(String type, String id, Identity requester, boolean checkAccess, String waitUntil);

    /**
     * The resource once the registry holds it in {@code state} (of {@code type} when given): at
     * once when it already is, else on the registry event that brings it there, never by polling;
     * empty when {@code timeout} passes first.
     */
    Mono<Resource> awaitState(String type, String id, String state, Duration timeout);

    record Lineage(Resource resource, List<Resource> builtFrom, List<Resource> dependents) {
    }

    Mono<Lineage> lineage(String id);
}
