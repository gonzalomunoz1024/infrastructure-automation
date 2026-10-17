package com.iaf.platform.catalog.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * Every generated route at one moment: for each published workflow its invoke route, its
 * {@code :batch} route (on the collection when the path ends in a parameter, see
 * {@link com.iaf.platform.workflows.domain.WorkflowDefinition#batchPath()}) and (with
 * {@code api.preview}) its {@code :preview} route; for each resource
 * type the {@code GET /api/v1/<plural>} and {@code GET /api/v1/<plural>/{id}} reads. Built from the
 * live catalog on every request, so a publish changes the routes without a restart. Paths whose
 * first segment is a platform noun are left out: platform routes always win.
 */
public record RouteTable(List<Route> routes) {

    public enum Kind {
        INVOKE, BATCH, PREVIEW, LIST_RESOURCES, GET_RESOURCE
    }

    /** A route and what it leads to: a workflow (invoke, batch, preview) or a resource type (reads). */
    public record Route(Kind kind, RouteTemplate template, WorkflowRecord workflow, ResourceTypeDefinition type) {
        public String method() {
            return template.method();
        }

        public String path() {
            return template.template();
        }

        public boolean isWorkflow() {
            return workflow != null;
        }
    }

    public record RouteMatch(Route route, Map<String, String> params) {
    }

    public static final String PREVIEW = ":preview";

    public static RouteTable of(List<WorkflowRecord> published, List<ResourceTypeDefinition> types) {
        List<Route> routes = new ArrayList<>();
        List<WorkflowRecord> workflows = new ArrayList<>(published == null ? List.of() : published);
        workflows.sort(Comparator.comparing(WorkflowRecord::name, Comparator.nullsLast(Comparator.naturalOrder())));
        for (WorkflowRecord w : workflows) {
            var d = w.definition();
            if (d == null) {
                continue;
            }
            String path = d.path();
            if (!ReservedNouns.isGenerated(path)) {
                continue;
            }
            RouteTemplate invoke;
            try {
                invoke = RouteTemplate.compile(d.method(), path);
            } catch (IllegalArgumentException e) {
                continue;
            }
            routes.add(new Route(Kind.INVOKE, invoke, w, null));
            routes.add(new Route(Kind.BATCH, RouteTemplate.compile("POST", d.batchPath()), w, null));
            if (d.previewEnabled()) {
                routes.add(new Route(Kind.PREVIEW, invoke.withSuffix("POST", PREVIEW), w, null));
            }
        }
        List<ResourceTypeDefinition> ts = new ArrayList<>(types == null ? List.of() : types);
        ts.sort(Comparator.comparing(ResourceTypeDefinition::plural, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ResourceTypeDefinition t : ts) {
            String base = t.readPath();
            if (!ReservedNouns.isGenerated(base)) {
                continue;
            }
            routes.add(new Route(Kind.LIST_RESOURCES, RouteTemplate.compile("GET", base), null, t));
            routes.add(new Route(Kind.GET_RESOURCE, RouteTemplate.compile("GET", base + "/{id}"), null, t));
        }
        return new RouteTable(List.copyOf(routes));
    }

    /**
     * The first route matching a request. Workflow routes come before resource reads, and within
     * a workflow the suffixed routes cannot collide with the plain one because a path parameter
     * never contains {@code :}.
     */
    public Optional<RouteMatch> match(String method, String path) {
        if (!ReservedNouns.isGenerated(path)) {
            return Optional.empty();
        }
        for (Route r : routes) {
            Optional<Map<String, String>> params = r.template().match(method, path);
            if (params.isPresent()) {
                return Optional.of(new RouteMatch(r, params.get()));
            }
        }
        return Optional.empty();
    }

    /** Whether any route has this path (any method), to tell 405 from 404. */
    public boolean knowsPath(String path) {
        return !allowedMethods(path).isEmpty();
    }

    /** The methods that exist on a path, for the {@code Allow} header of a 405. */
    public List<String> allowedMethods(String path) {
        return routes.stream().filter(r -> r.template().matchPath(path).isPresent()).map(Route::method).distinct().toList();
    }

    public List<Route> ofKind(Kind kind) {
        return routes.stream().filter(r -> r.kind() == kind).toList();
    }
}
