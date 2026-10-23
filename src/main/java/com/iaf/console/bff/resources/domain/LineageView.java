package com.iaf.console.bff.resources.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** What a resource was built from and what depends on it, with wfrns on the nodes and edges. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageView(ResourceRef resource, List<ResourceRef> nodes, List<Edge> edges) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(String from, String to, String via) {
    }

    public LineageView {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A resource the platform knows nothing about yet: itself, alone. */
    public static LineageView alone(StateResource resource) {
        ResourceRef self = ResourceRef.of(resource);
        return new LineageView(self, List.of(self), List.of());
    }
}
