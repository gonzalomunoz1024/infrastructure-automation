package com.iaf.platform.resources.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.workflows.domain.DependencyRules;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * Demo resources for the registry, derived from the content itself so the platform stays
 * generic: a type depends on the types its producing workflow {@code use}s and on the types
 * produced by the workflows it {@code run}s. A type gets one resource per example name in its
 * schema (at least two), a composite type (two or more dependencies) one built from the first two
 * ready resources of each; a root type that other workflows pick from the registry with a
 * {@code use} step (a template) has its second copy deprecated when it knows that state, while a
 * type created on demand only has live copies. Every resource is built from ready resources of
 * the types it depends on, so lineage has data on day one.
 */
public final class DemoResources {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final java.util.regex.Pattern REFERENCE_NAME = java.util.regex.Pattern.compile("(To|Id|Ref|From|Of)$");
    public static final String READY = "ready";
    public static final String DEPRECATED = "deprecated";
    /** How many ready resources of each dependency a composite resource is built from. */
    static final int COMPOSITE_PARTS = 2;

    private DemoResources() {
    }

    /** For each type, the types it is built from, as the workflows describe it ({@link DependencyRules}). */
    public static Map<String, Set<String>> dependencies(List<ResourceTypeDefinition> types, List<WorkflowDefinition> workflows) {
        return graph(types, workflows).typeDependencies();
    }

    /** The types some workflow picks from the registry with a {@code use} step. */
    public static Set<String> usedTypes(List<WorkflowDefinition> workflows) {
        Set<String> used = new TreeSet<>();
        graph(List.of(), workflows).consumes().stream().filter(l -> l.via() == DependencyRules.Via.USE).forEach(l -> used.add(l.type()));
        return used;
    }

    /** The dependency graph of the content: every type with the workflow its file names as producer. */
    public static DependencyRules.Graph graph(List<ResourceTypeDefinition> types, List<WorkflowDefinition> workflows) {
        Map<String, String> producedBy = new HashMap<>();
        types.forEach(t -> producedBy.put(t.name(), t.producedBy()));
        return DependencyRules.of(workflows == null ? List.of() : workflows, producedBy);
    }

    /** Types in dependency order (what a type is built from comes first), ties by name. */
    public static List<String> order(Map<String, Set<String>> deps) {
        List<String> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> remaining = new TreeSet<>(deps.keySet());
        while (!remaining.isEmpty()) {
            String next = null;
            for (String t : remaining) {
                if (done.containsAll(deps.get(t))) {
                    next = t;
                    break;
                }
            }
            if (next == null) {
                next = remaining.iterator().next();   // a cycle: take the first by name
            }
            remaining.remove(next);
            done.add(next);
            out.add(next);
        }
        return out;
    }

    public static List<Resource> seed(List<ResourceTypeDefinition> types, List<WorkflowDefinition> workflows, Instant now) {
        Map<String, ResourceTypeDefinition> byName = new LinkedHashMap<>();
        for (ResourceTypeDefinition t : types) {
            byName.put(t.name(), t);
        }
        Map<String, String> versions = new HashMap<>();
        for (WorkflowDefinition w : workflows == null ? List.<WorkflowDefinition>of() : workflows) {
            versions.put(w.name(), w.version());
        }
        Map<String, Set<String>> deps = dependencies(types, workflows);
        Set<String> used = usedTypes(workflows);
        Map<String, List<Resource>> created = new LinkedHashMap<>();
        List<Resource> out = new ArrayList<>();
        int serial = 0;
        for (String name : order(deps)) {
            ResourceTypeDefinition t = byName.get(name);
            Set<String> d = deps.getOrDefault(name, Set.of());
            int count = d.size() >= 2 ? 1 : Math.max(2, exampleNames(t));
            List<Resource> ofType = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                serial++;
                boolean deprecated = i == 2 && d.isEmpty() && used.contains(name) && t.states() != null && t.states().contains(DEPRECATED);
                String state = deprecated ? DEPRECATED : READY;
                Map<String, List<Resource>> from = new LinkedHashMap<>();
                for (String dep : d) {
                    List<Resource> candidates = created.getOrDefault(dep, List.of()).stream().filter(r -> READY.equals(r.status())).toList();
                    if (candidates.isEmpty()) {
                        continue;
                    }
                    if (d.size() == 1) {
                        from.put(dep, List.of(candidates.get((i - 1) % candidates.size())));
                    } else {
                        from.put(dep, candidates.subList(0, Math.min(COMPOSITE_PARTS, candidates.size())));
                    }
                }
                String id = name + "-demo" + String.format("%02d", i);
                ObjectNode attributes = attributes(t, i, from);
                List<String> builtFrom = from.values().stream().flatMap(List::stream).map(Resource::id).toList();
                Instant createdAt = now.minus(Duration.ofDays(30L - serial)).minus(Duration.ofHours(i));
                String producer = t.producedBy();
                Resource r = new Resource(id, name, attributes.path("name").asText(id), state, attributes, t.owner(), t.defaultVisibility(),
                        new Resource.Origin("job_demo" + String.format("%03d", serial), producer, producer == null ? null : versions.get(producer)),
                        builtFrom, createdAt, deprecated ? createdAt.plus(Duration.ofDays(3)) : createdAt);
                ofType.add(r);
                out.add(r);
            }
            created.put(name, ofType);
        }
        return out;
    }

    /** How many example names the type's schema gives ({@code properties.name.examples}). */
    static int exampleNames(ResourceTypeDefinition t) {
        JsonNode examples = t.schema() == null ? null : t.schema().path("properties").path("name").path("examples");
        return examples != null && examples.isArray() ? examples.size() : 0;
    }

    /** Plausible attributes for the type's schema; properties named after a dependency get its id. */
    static ObjectNode attributes(ResourceTypeDefinition t, int i, Map<String, List<Resource>> from) {
        ObjectNode a = F.objectNode();
        JsonNode schema = t.schema();
        JsonNode props = schema == null ? null : schema.get("properties");
        if (props == null || !props.isObject()) {
            a.put("name", t.name() + "-" + i);
            return a;
        }
        Set<String> assigned = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> it = props.properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String p = e.getKey();
            JsonNode s = e.getValue();
            List<Resource> byName = from.get(p);
            if (byName == null) {
                for (var entry : from.entrySet()) {
                    if (p.equalsIgnoreCase(entry.getKey() + "s") || p.equalsIgnoreCase(entry.getKey() + "Id")) {
                        byName = entry.getValue();
                        from.put(p, entry.getValue());
                        break;
                    }
                }
            }
            if (byName != null && !byName.isEmpty()) {
                assigned.add(p);
                if ("array".equals(s.path("type").asText())) {
                    ArrayNode arr = a.putArray(p);
                    byName.forEach(r -> arr.add(r.id()));
                } else {
                    a.put(p, byName.get(0).id());
                }
                continue;
            }
            a.set(p, value(t, p, s, i));
        }
        // a dependency nobody referenced by name goes into an unclaimed string array, else into a
        // string property whose name reads as a reference (attachedTo, parentId, sourceRef, builtFrom)
        for (var entry : from.entrySet()) {
            boolean referenced = assigned.stream().anyMatch(p -> p.equalsIgnoreCase(entry.getKey()) || p.equalsIgnoreCase(entry.getKey() + "s") || p.equalsIgnoreCase(entry.getKey() + "Id"));
            if (referenced) {
                continue;
            }
            String array = null;
            String reference = null;
            Iterator<Map.Entry<String, JsonNode>> again = props.properties().iterator();
            while (again.hasNext()) {
                Map.Entry<String, JsonNode> e = again.next();
                if (assigned.contains(e.getKey())) {
                    continue;
                }
                String type = e.getValue().path("type").asText("string");
                if (array == null && "array".equals(type) && "string".equals(e.getValue().path("items").path("type").asText("string"))) {
                    array = e.getKey();
                } else if (reference == null && "string".equals(type) && REFERENCE_NAME.matcher(e.getKey()).find()) {
                    reference = e.getKey();
                }
            }
            if (array != null) {
                ArrayNode arr = a.putArray(array);
                entry.getValue().forEach(r -> arr.add(r.id()));
                assigned.add(array);
            } else if (reference != null) {
                a.put(reference, entry.getValue().get(0).id());
                assigned.add(reference);
            }
        }
        if (!a.has("name") && props.has("name")) {
            a.put("name", t.name() + "-" + i);
        }
        return a;
    }

    private static JsonNode value(ResourceTypeDefinition t, String p, JsonNode s, int i) {
        if (s.has("example")) {
            return s.get("example");
        }
        if (s.path("examples").isArray() && s.get("examples").size() > 0) {
            return s.get("examples").get((i - 1) % s.get("examples").size());
        }
        if (s.has("enum") && s.get("enum").size() > 0) {
            return s.get("enum").get((i - 1) % s.get("enum").size());
        }
        if (s.has("default")) {
            return s.get("default");
        }
        String type = s.path("type").asText("string");
        return switch (type) {
            case "integer" -> F.numberNode(s.has("minimum") ? Math.max(s.get("minimum").asLong(), 1) * 4 * i : 4L * i);
            case "number" -> F.numberNode(1.5 * i);
            case "boolean" -> F.booleanNode(true);
            case "array" -> F.arrayNode();
            case "object" -> F.objectNode();
            default -> F.textNode(switch (p) {
                case "name" -> t.name() + "-" + i;
                case "environment", "stage" -> i == 1 ? "prod" : "dev";
                case "owner" -> t.owner() == null ? "team-platform" : t.owner();
                case "url" -> "https://" + t.name() + "-" + i + ".example.com";
                default -> p + "-" + i;
            });
        };
    }
}
