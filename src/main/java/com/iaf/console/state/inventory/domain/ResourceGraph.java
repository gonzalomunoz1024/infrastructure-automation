package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The resource map: the resources of one or more accounts and the lineage between them, for a
 * zoomable graph coloured by state or drift. Parents outside the map that the caller may see are
 * drawn as {@code context} nodes; resources of other accounts on either side of an edge are
 * summed up in {@code external} by account and type; parents the console does not manage are
 * listed by registry id in {@code unmanaged}.
 */
public record ResourceGraph(List<Node> nodes, List<Lineage.Edge> edges, List<External> external, List<String> unmanaged, boolean truncated) {

    /** The most nodes one map holds. */
    public static final int MAX_NODES = 2000;

    public ResourceGraph {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        external = List.copyOf(external);
        unmanaged = List.copyOf(unmanaged);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String wfrn, String type, String service, String name, ResourceState state, String accountId, String region,
            ResourceRef.DriftRef drift, String observedStatus, Map<String, String> tags, Instant updatedAt, Boolean context) {

        public static Node of(Resource r, boolean context) {
            ResourceRef ref = ResourceRef.of(r);
            return new Node(ref.wfrn(), ref.type(), ref.service(), ref.name(), ref.state(), ref.accountId(), ref.region(), ref.drift(),
                    ref.observedStatus(), r.tags(), r.updatedAt(), context ? Boolean.TRUE : null);
        }
    }

    /** Resources of another account linked to the map: {@code builtFrom} when map nodes were built from them, {@code dependent} when built from map nodes. */
    public record External(String accountId, String type, String direction, int count) {
    }
}
