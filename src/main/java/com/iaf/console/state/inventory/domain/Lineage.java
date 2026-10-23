package com.iaf.console.state.inventory.domain;

import java.util.List;

/**
 * What a resource was built from and what was built from it, as far as {@code depth} hops in both
 * directions: the resource first in {@code nodes}, its direct neighbours in {@code builtFrom} and
 * {@code dependents}, and {@code edges} from the resource built from to the one built
 * ({@code via} the workflow that registered the latter). Only the caller's account is drawn:
 * {@code hidden} counts the resources of other accounts the walk reached, and {@code unmanaged}
 * lists the registry ids of parents the console does not manage.
 */
public record Lineage(ResourceRef resource, List<ResourceRef> builtFrom, List<ResourceRef> dependents, List<ResourceRef> nodes, List<Edge> edges,
        List<String> unmanaged, int hidden, boolean truncated) {

    public Lineage {
        builtFrom = List.copyOf(builtFrom);
        dependents = List.copyOf(dependents);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        unmanaged = List.copyOf(unmanaged);
    }

    /** One relationship: {@code to} was built from {@code from}. */
    public record Edge(String from, String to, String via) {
    }
}
