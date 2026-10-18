package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.workflows.domain.DependencyRules.Graph;
import com.iaf.platform.workflows.domain.DependencyRules.RunLink;
import com.iaf.platform.workflows.domain.DependencyRules.TypeLink;

/**
 * What a new version of a workflow promises its consumers, compared with the version they were
 * built against. A change is breaking when a consumer written for the previous version can stop
 * working: an input removed, newly required, retyped or narrowed; an output removed; a resource
 * type no longer registered, or registered without an attribute it used to get. Adding things
 * never breaks anyone. Breaking changes need a new major version, because the range a run step
 * pins ({@code ^2}) is what keeps its consumers on the old contract.
 */
public final class ContractRules {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private ContractRules() {
    }

    /** A consumer a new version reaches, and how. */
    public record Consumer(String workflow, String via, String at, String range) {

        /** {@code web-environment (run ^2 at vm)}, {@code patch-cve (use of vm at useVm)}. */
        public String describe() {
            String how = switch (via) {
                case "run" -> "run " + (range == null ? "with no version" : range);
                case "orCreateWith" -> "orCreateWith";
                default -> via;
            };
            return workflow + " (" + how + (at == null ? "" : " at " + at) + ")";
        }
    }

    /** Every change in {@code next} that can break a consumer of {@code previous}, in reading order. */
    public static List<String> breakingChanges(WorkflowDefinition previous, WorkflowDefinition next) {
        List<String> out = new ArrayList<>();
        if (previous == null || next == null) {
            return out;
        }
        inputs(previous.inputSchema(), next.inputSchema(), out);
        Set<String> outputs = outputs(next);
        outputs(previous).stream().filter(o -> !outputs.contains(o)).forEach(o -> out.add("output '" + o + "' was removed"));
        Map<String, Set<String>> before = registered(previous);
        Map<String, Set<String>> after = registered(next);
        before.forEach((type, attributes) -> {
            if (!after.containsKey(type)) {
                out.add("it no longer registers " + type + " resources");
            } else {
                attributes.stream().filter(a -> !after.get(type).contains(a))
                        .forEach(a -> out.add(type + " resources no longer get attribute '" + a + "'"));
            }
        });
        return out;
    }

    /** Whether {@code next} starts a new major version after {@code previous}. */
    public static boolean isMajorBump(String previous, String next) {
        return Semver.parse(next).major() > Semver.parse(previous).major();
    }

    /**
     * The consumers that pick up {@code version} of {@code workflow}: run steps whose range accepts
     * it (a step with no range runs the current major, so it does), {@code orCreateWith} inputs,
     * and the workflows that use a resource type it registers.
     */
    public static List<Consumer> affected(Graph graph, String workflow, String version, Collection<String> types) {
        List<Consumer> out = new ArrayList<>();
        for (RunLink l : graph.consumersOf(workflow)) {
            if (l.range() == null || Semver.parse(version).satisfies(l.range())) {
                out.add(new Consumer(l.consumer(), l.via().json(), l.at(), l.range()));
            }
        }
        for (String type : new TreeSet<>(types)) {
            for (TypeLink l : graph.consumersOfType(type)) {
                if (!l.workflow().equals(workflow)) {
                    out.add(new Consumer(l.workflow(), l.via() == DependencyRules.Via.USE ? "use of " + type : "input of type " + type, l.at(), null));
                }
            }
        }
        return out;
    }

    /** The run steps that stay on an older major: their range does not accept {@code version}. */
    public static List<Consumer> staying(Graph graph, String workflow, String version) {
        return graph.consumersOf(workflow).stream()
                .filter(l -> l.range() != null && !Semver.parse(version).satisfies(l.range()))
                .map(l -> new Consumer(l.consumer(), l.via().json(), l.at(), l.range()))
                .toList();
    }

    /** The resource types a definition registers (its register steps), with the attribute keys each one writes. */
    static Map<String, Set<String>> registered(WorkflowDefinition def) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Step s : def.allSteps()) {
            String type = s.type() == StepType.REGISTER ? s.withText("type") : null;
            if (type == null || type.isBlank()) {
                continue;
            }
            Set<String> keys = out.computeIfAbsent(type, t -> new LinkedHashSet<>());
            JsonNode attributes = s.withNode("attributes");
            if (attributes != null && attributes.isObject()) {
                attributes.fieldNames().forEachRemaining(keys::add);
            }
        }
        return out;
    }

    private static Set<String> outputs(WorkflowDefinition def) {
        return def.workflow() == null || def.workflow().outputs() == null ? Set.of() : new LinkedHashSet<>(def.workflow().outputs().keySet());
    }

    // ------------------------------------------------------------------ inputs

    private static void inputs(JsonNode before, JsonNode after, List<String> out) {
        JsonNode was = properties(before);
        JsonNode now = properties(after);
        Set<String> required = names(before == null ? null : before.get("required"));
        was.fieldNames().forEachRemaining(f -> {
            JsonNode p = now.get(f);
            if (p == null) {
                out.add("input '" + f + "' was removed");
                return;
            }
            JsonNode old = was.get(f);
            if (!widens(old.get("type"), p.get("type"))) {
                out.add("input '" + f + "' changed type from " + typeText(old.get("type")) + " to " + typeText(p.get("type")));
            }
            String oldType = old.path("x-resource").path("type").asText(null);
            String newType = p.path("x-resource").path("type").asText(null);
            if (newType != null && !newType.equals(oldType)) {
                out.add("input '" + f + "' now points at type " + newType + (oldType == null ? "" : " instead of " + oldType));
            }
            JsonNode oldEnum = old.get("enum");
            JsonNode newEnum = p.get("enum");
            if (newEnum != null && newEnum.isArray()) {
                if (oldEnum == null || !oldEnum.isArray()) {
                    out.add("input '" + f + "' now only accepts " + newEnum);
                } else {
                    List<String> dropped = new ArrayList<>();
                    oldEnum.forEach(v -> {
                        if (!contains(newEnum, v)) {
                            dropped.add(v.toString());
                        }
                    });
                    if (!dropped.isEmpty()) {
                        out.add("input '" + f + "' no longer accepts " + String.join(", ", dropped));
                    }
                }
            }
        });
        for (String f : names(after == null ? null : after.get("required"))) {
            if (!required.contains(f) && !now.path(f).has("default")) {
                out.add("input '" + f + "' is now required");
            }
        }
    }

    private static JsonNode properties(JsonNode schema) {
        JsonNode p = schema == null ? null : schema.get("properties");
        return p != null && p.isObject() ? p : F.objectNode();
    }

    private static Set<String> names(JsonNode list) {
        Set<String> out = new LinkedHashSet<>();
        if (list != null && list.isArray()) {
            list.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** Every value the old type accepted is still accepted ({@code integer} fits in {@code number}; no type accepts anything). */
    static boolean widens(JsonNode before, JsonNode after) {
        if (after == null || after.isNull()) {
            return true;
        }
        if (before == null || before.isNull()) {
            return false;
        }
        Set<String> now = names(after.isArray() ? after : F.arrayNode().add(after.asText()));
        Set<String> was = names(before.isArray() ? before : F.arrayNode().add(before.asText()));
        return was.stream().allMatch(t -> now.contains(t) || ("integer".equals(t) && now.contains("number")));
    }

    private static String typeText(JsonNode type) {
        if (type == null || type.isNull()) {
            return "any";
        }
        if (!type.isArray()) {
            return type.asText();
        }
        List<String> out = new ArrayList<>();
        type.forEach(t -> out.add(t.asText()));
        return String.join(" or ", out);
    }

    private static boolean contains(JsonNode values, JsonNode v) {
        for (JsonNode e : values) {
            if (e.equals(v) || (e.isNumber() && v.isNumber() && e.asDouble() == v.asDouble())) {
                return true;
            }
        }
        return false;
    }
}
