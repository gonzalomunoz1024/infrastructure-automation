package com.iaf.console.state.stack.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reads a {@code kind: Blueprint} document and checks what the state service relies on: the
 * shape, references that name what exists and may stand where they are, and no cycle. The hub's
 * {@code npm run check} holds blueprints to much more (the pinned types and workflows); this is
 * the service's own guard, so a bad copy fails at start-up instead of in the middle of a stack.
 */
public final class BlueprintParser {

    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9-]{2,40}$");
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern PARAMETER = Pattern.compile("^[a-z][a-zA-Z0-9]{0,31}$");
    private static final Set<String> TYPES = Set.of("string", "integer", "boolean");

    private BlueprintParser() {
    }

    /** The blueprint a document spells; an IllegalArgumentException listing every problem otherwise. */
    public static Blueprint parse(JsonNode doc) {
        List<String> problems = new ArrayList<>();
        if (doc == null || !doc.isObject()) {
            throw new IllegalArgumentException("a blueprint is a mapping");
        }
        if (!"iaf.console/v1".equals(doc.path("apiVersion").asText()) || !"Blueprint".equals(doc.path("kind").asText())) {
            problems.add("apiVersion iaf.console/v1 and kind Blueprint");
        }
        JsonNode meta = doc.path("metadata");
        String name = meta.path("name").asText(null);
        if (name == null || !NAME.matcher(name).matches()) {
            problems.add("metadata.name is [a-z][a-z0-9-]{2,40}");
        }
        JsonNode spec = doc.path("spec");

        Map<String, BlueprintParameter> parameters = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : spec.path("parameters").properties()) {
            JsonNode p = e.getValue();
            String at = "parameter " + e.getKey();
            if (!PARAMETER.matcher(e.getKey()).matches()) {
                problems.add(at + ": a parameter name is [a-z][a-zA-Z0-9]{0,31}");
            }
            String type = p.path("type").asText(null);
            if (!TYPES.contains(type)) {
                problems.add(at + ": type is string, integer or boolean");
            }
            String pattern = text(p, "pattern");
            if (pattern != null) {
                try {
                    Pattern.compile(pattern);
                } catch (PatternSyntaxException ex) {
                    problems.add(at + ": pattern does not compile");
                }
            }
            List<JsonNode> enumValues = null;
            if (p.has("enum")) {
                enumValues = new ArrayList<>();
                p.get("enum").forEach(enumValues::add);
            }
            BlueprintParameter.ResourceHint hint = p.has("x-resource") ? new BlueprintParameter.ResourceHint(p.path("x-resource").path("type").asText(null)) : null;
            parameters.put(e.getKey(), new BlueprintParameter(type, p.path("title").asText(e.getKey()), text(p, "description"),
                    p.has("default") ? p.get("default") : null, enumValues, pattern, longOf(p, "minimum"), longOf(p, "maximum"), hint));
        }

        JsonNode resourcesNode = spec.path("resources");
        Set<String> ids = new LinkedHashSet<>();
        resourcesNode.fieldNames().forEachRemaining(ids::add);
        if (ids.isEmpty()) {
            problems.add("spec.resources holds at least one resource");
        }
        Map<String, BlueprintResource> resources = new LinkedHashMap<>();
        for (String id : ids) {
            JsonNode r = resourcesNode.get(id);
            String at = "resources/" + id;
            if (!ID.matcher(id).matches()) {
                problems.add(at + ": a resource id is [a-z][a-z0-9-]{0,31}");
            }
            String type = r.path("type").asText(null);
            if (type == null || type.isBlank()) {
                problems.add(at + ": a type is required");
            }
            ObjectNode input = r.has("input") && r.get("input").isObject() ? (ObjectNode) r.get("input").deepCopy() : null;
            ObjectNode deleteInput = r.has("deleteInput") && r.get("deleteInput").isObject() ? (ObjectNode) r.get("deleteInput").deepCopy() : null;
            Map<String, String> tags = new LinkedHashMap<>();
            r.path("tags").properties().forEach(t -> tags.put(t.getKey(), t.getValue().asText()));
            DeletionPolicy policy = r.has("deletionPolicy") ? DeletionPolicy.of(r.get("deletionPolicy").asText()) : DeletionPolicy.DELETE;
            if (policy == null) {
                problems.add(at + ": deletionPolicy is delete or retain");
                policy = DeletionPolicy.DELETE;
            }
            if (policy == DeletionPolicy.RETAIN && deleteInput != null) {
                problems.add(at + ": deleteInput is for a resource teardown deletes");
            }
            Set<String> requires = new LinkedHashSet<>();
            for (JsonNode dep : r.path("dependsOn")) {
                if (!ids.contains(dep.asText())) {
                    problems.add(at + ": dependsOn " + dep.asText() + " names no resource");
                } else if (dep.asText().equals(id)) {
                    problems.add(at + ": dependsOn names the resource itself");
                } else {
                    requires.add(dep.asText());
                }
            }
            for (String expression : References.in(input)) {
                References.problems(expression, id, parameters.keySet(), ids, false).forEach(p -> problems.add(at + "/input: " + p));
            }
            for (String value : tags.values()) {
                for (String expression : References.in(com.fasterxml.jackson.databind.node.TextNode.valueOf(value))) {
                    References.problems(expression, id, parameters.keySet(), ids, false).forEach(p -> problems.add(at + "/tags: " + p));
                }
            }
            for (String expression : References.in(deleteInput)) {
                References.problems(expression, id, parameters.keySet(), ids, true).forEach(p -> problems.add(at + "/deleteInput: " + p));
            }
            References.resourcesIn(input).stream().filter(ids::contains).filter(d -> !d.equals(id)).forEach(requires::add);
            tags.values().forEach(v -> References.resourcesIn(com.fasterxml.jackson.databind.node.TextNode.valueOf(v)).stream()
                    .filter(ids::contains).filter(d -> !d.equals(id)).forEach(requires::add));
            resources.put(id, new BlueprintResource(id, type, text(r, "title"), input, tags, List.copyOf(requires), policy, deleteInput));
        }

        Map<String, BlueprintOutput> outputs = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : spec.path("outputs").properties()) {
            String value = e.getValue().path("value").asText("");
            for (String expression : References.in(com.fasterxml.jackson.databind.node.TextNode.valueOf(value))) {
                References.problems(expression, null, parameters.keySet(), ids, false).forEach(p -> problems.add("outputs/" + e.getKey() + ": " + p));
            }
            outputs.put(e.getKey(), new BlueprintOutput(e.getValue().path("title").asText(e.getKey()), text(e.getValue(), "description"), value));
        }

        String cycle = cycle(resources);
        if (cycle != null) {
            problems.add("resources: a cycle " + cycle);
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("blueprint " + name + ": " + String.join("; ", problems));
        }
        List<String> tags = new ArrayList<>();
        meta.path("tags").forEach(t -> tags.add(t.asText()));
        return new Blueprint(name, meta.path("version").asText("0.0.0"), meta.path("title").asText(name), meta.path("description").asText(""),
                meta.path("owner").asText(""), tags, parameters, resources, outputs);
    }

    private static String cycle(Map<String, BlueprintResource> resources) {
        Set<String> done = new HashSet<>();
        for (String id : resources.keySet()) {
            String found = visit(id, resources, done, new ArrayList<>());
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String visit(String id, Map<String, BlueprintResource> resources, Set<String> done, List<String> path) {
        if (path.contains(id)) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            loop.add(id);
            return String.join(" -> ", loop);
        }
        if (done.contains(id)) {
            return null;
        }
        path.add(id);
        for (String dep : resources.get(id).dependsOn()) {
            String found = visit(dep, resources, done, path);
            if (found != null) {
                return found;
            }
        }
        path.remove(path.size() - 1);
        done.add(id);
        return null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Long longOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || !v.isNumber() ? null : v.asLong();
    }
}
