package com.iaf.platform.resources.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The lineage graph around a resource, walked over {@code builtFrom} in both directions:
 * depth-first, each resource once (a cycle ends where it started), at most {@link #MAX_DEPTH}
 * hops from the resource and {@link #MAX_NODES} resources in all. An edge {@code from -> to} says
 * {@code to} was built from {@code from}, {@code via} the workflow that registered {@code to}
 * when it is known.
 */
public final class ResourceGraph {

    public static final int MAX_DEPTH = 32;
    public static final int MAX_NODES = 1_000;

    private ResourceGraph() {
    }

    public record Edge(String from, String to, String via) {
    }

    /** The resources reached, in the order they were met, and the edges between them. */
    public record Walk(List<Resource> nodes, List<Edge> edges) {
    }

    /** Everything built from the resource, directly or through others ({@code builtFrom} lists a resource's direct dependents). */
    public static Walk descendants(Resource root, Function<String, List<Resource>> builtFrom) {
        List<Resource> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(root.id());
        down(root.id(), 0, builtFrom, seen, nodes, edges);
        return new Walk(List.copyOf(nodes), List.copyOf(edges));
    }

    private static void down(String id, int depth, Function<String, List<Resource>> builtFrom, Set<String> seen, List<Resource> nodes, List<Edge> edges) {
        if (depth >= MAX_DEPTH) {
            return;
        }
        for (Resource r : builtFrom.apply(id)) {
            if (nodes.size() >= MAX_NODES) {
                return;
            }
            edges.add(new Edge(id, r.id(), via(r)));
            if (seen.add(r.id())) {
                nodes.add(r);
                down(r.id(), depth + 1, builtFrom, seen, nodes, edges);
            }
        }
    }

    /** What the resource was built from, directly or through others; ids the registry does not know are skipped. */
    public static Walk ancestors(Resource root, Function<String, Optional<Resource>> resource) {
        List<Resource> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(root.id());
        up(root, 0, resource, seen, nodes, edges);
        return new Walk(List.copyOf(nodes), List.copyOf(edges));
    }

    private static void up(Resource r, int depth, Function<String, Optional<Resource>> resource, Set<String> seen, List<Resource> nodes, List<Edge> edges) {
        if (depth >= MAX_DEPTH || r.builtFrom() == null) {
            return;
        }
        for (String id : r.builtFrom()) {
            if (nodes.size() >= MAX_NODES) {
                return;
            }
            Optional<Resource> source = id == null ? Optional.empty() : resource.apply(id);
            if (source.isEmpty()) {
                continue;
            }
            edges.add(new Edge(id, r.id(), via(r)));
            if (seen.add(id)) {
                nodes.add(source.get());
                up(source.get(), depth + 1, resource, seen, nodes, edges);
            }
        }
    }

    /** The workflow that registered the resource, when known. */
    public static String via(Resource r) {
        return r == null || r.createdBy() == null ? null : r.createdBy().workflow();
    }
}
