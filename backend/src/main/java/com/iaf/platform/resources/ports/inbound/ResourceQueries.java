package com.iaf.platform.resources.ports.inbound;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.domain.ResourceGraph;
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

    /**
     * The lineage of a resource. {@code builtFrom} and {@code dependents} are its direct
     * neighbours; {@code nodes} and {@code edges} are the whole graph around it in both directions
     * ({@link ResourceGraph}: each resource once, bounded), the resource itself first among the
     * nodes. An edge {@code from -> to} says {@code to} was built from {@code from}, {@code via}
     * the workflow that registered {@code to} when known.
     */
    record Lineage(Resource resource, List<Resource> builtFrom, List<Resource> dependents, List<Resource> nodes, List<ResourceGraph.Edge> edges) {

        /** The direct neighbourhood only: the graph is the two lists around the resource. */
        public Lineage(Resource resource, List<Resource> builtFrom, List<Resource> dependents) {
            this(resource, builtFrom, dependents, nodesOf(resource, builtFrom, dependents), edgesOf(resource, builtFrom, dependents));
        }

        private static List<Resource> nodesOf(Resource resource, List<Resource> builtFrom, List<Resource> dependents) {
            Map<String, Resource> nodes = new LinkedHashMap<>();
            nodes.put(resource.id(), resource);
            for (Resource r : builtFrom == null ? List.<Resource>of() : builtFrom) {
                nodes.putIfAbsent(r.id(), r);
            }
            for (Resource r : dependents == null ? List.<Resource>of() : dependents) {
                nodes.putIfAbsent(r.id(), r);
            }
            return List.copyOf(nodes.values());
        }

        private static List<ResourceGraph.Edge> edgesOf(Resource resource, List<Resource> builtFrom, List<Resource> dependents) {
            List<ResourceGraph.Edge> edges = new ArrayList<>();
            for (Resource r : builtFrom == null ? List.<Resource>of() : builtFrom) {
                edges.add(new ResourceGraph.Edge(r.id(), resource.id(), ResourceGraph.via(resource)));
            }
            for (Resource r : dependents == null ? List.<Resource>of() : dependents) {
                edges.add(new ResourceGraph.Edge(resource.id(), r.id(), ResourceGraph.via(r)));
            }
            return List.copyOf(edges);
        }
    }

    Mono<Lineage> lineage(String id);
}
