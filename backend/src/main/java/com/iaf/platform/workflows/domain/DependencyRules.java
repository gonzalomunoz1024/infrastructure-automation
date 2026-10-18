package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * How workflows depend on each other, read from the files alone so the platform never needs to
 * know what a resource is. A workflow produces a resource type when the type names it in
 * {@code producedBy} or when one of its {@code register} steps records that type. A workflow
 * consumes a type through an input marked {@code x-resource} or a {@code use} step. A workflow
 * runs another one through a {@code run} step (with its version range) or through an
 * {@code x-resource} input with {@code orCreateWith}, which builds the resource first when the
 * requester asks for a new one. Cycles are cycles of runs: a workflow that ends up starting
 * itself would never finish.
 */
public final class DependencyRules {

    private DependencyRules() {
    }

    /** Where a link comes from. */
    public enum Via {
        PRODUCED_BY("producedBy"),
        REGISTER("register"),
        INPUT("input"),
        USE("use"),
        RUN("run"),
        OR_CREATE_WITH("orCreateWith");

        private final String key;

        Via(String key) {
            this.key = key;
        }

        @JsonValue
        public String json() {
            return key;
        }
    }

    /** A workflow tied to a resource type; {@code at} is the step id or input field (null for producedBy). */
    public record TypeLink(String type, String workflow, Via via, String at) {
    }

    /**
     * {@code consumer} starts {@code producer} as a child job. {@code range} is the run step's
     * version range (null when the step names none, and for orCreateWith); {@code at} is the step
     * id or the input field.
     */
    public record RunLink(String consumer, String producer, String range, Via via, String at) {
    }

    /** The links of a set of workflows and resource types. */
    public record Graph(List<TypeLink> produces, List<TypeLink> consumes, List<RunLink> runs, Map<String, String> producedBy,
            List<List<String>> cycles) {

        /** The workflows that produce a type, by name. */
        public Set<String> producersOf(String type) {
            Set<String> out = new TreeSet<>();
            produces.stream().filter(l -> l.type().equals(type)).forEach(l -> out.add(l.workflow()));
            return out;
        }

        /** The types a workflow produces. */
        public Set<String> typesProducedBy(String workflow) {
            Set<String> out = new TreeSet<>();
            produces.stream().filter(l -> l.workflow().equals(workflow)).forEach(l -> out.add(l.type()));
            return out;
        }

        /** The types a workflow consumes (inputs and use steps). */
        public Set<String> typesConsumedBy(String workflow) {
            Set<String> out = new TreeSet<>();
            consumes.stream().filter(l -> l.workflow().equals(workflow)).forEach(l -> out.add(l.type()));
            return out;
        }

        /** Who consumes a type, and where. */
        public List<TypeLink> consumersOfType(String type) {
            return consumes.stream().filter(l -> l.type().equals(type)).toList();
        }

        /** The links of the workflows that run this one (run steps and orCreateWith inputs). */
        public List<RunLink> consumersOf(String workflow) {
            return runs.stream().filter(r -> r.producer().equals(workflow)).toList();
        }

        /** The workflows this one runs. */
        public List<RunLink> runsOf(String workflow) {
            return runs.stream().filter(r -> r.consumer().equals(workflow)).toList();
        }

        /** Every type a link or a declaration names, sorted. */
        public Set<String> types() {
            Set<String> out = new TreeSet<>(producedBy.keySet());
            produces.forEach(l -> out.add(l.type()));
            consumes.forEach(l -> out.add(l.type()));
            return out;
        }

        /** The cycles a workflow is part of. */
        public List<List<String>> cyclesThrough(String workflow) {
            return cycles.stream().filter(c -> c.contains(workflow)).toList();
        }

        /**
         * For each declared type, the types it is built from: the types its declared producer
         * picks with {@code use} steps and the types produced by the workflows that producer runs
         * with {@code run} steps. Types nobody declared are left out.
         */
        public Map<String, Set<String>> typeDependencies() {
            Map<String, Set<String>> deps = new TreeMap<>();
            for (Map.Entry<String, String> t : producedBy.entrySet()) {
                Set<String> d = new TreeSet<>();
                String producer = t.getValue();
                if (producer != null) {
                    consumes.stream().filter(l -> l.workflow().equals(producer) && l.via() == Via.USE).forEach(l -> d.add(l.type()));
                    runs.stream().filter(r -> r.consumer().equals(producer) && r.via() == Via.RUN)
                            .forEach(r -> d.addAll(typesProducedBy(r.producer())));
                }
                d.remove(t.getKey());
                d.retainAll(producedBy.keySet());
                deps.put(t.getKey(), d);
            }
            return deps;
        }
    }

    /**
     * The graph of the given workflows. {@code producedBy} maps every known type to the workflow
     * its file names in {@code producedBy} (the value may be null).
     */
    public static Graph of(Collection<WorkflowDefinition> workflows, Map<String, String> producedBy) {
        Map<String, String> declared = new TreeMap<>();
        if (producedBy != null) {
            producedBy.forEach((type, workflow) -> {
                if (type != null) {
                    declared.put(type, workflow);
                }
            });
        }
        List<TypeLink> produces = new ArrayList<>();
        List<TypeLink> consumes = new ArrayList<>();
        List<RunLink> runs = new ArrayList<>();
        declared.forEach((type, workflow) -> {
            if (workflow != null && !workflow.isBlank()) {
                produces.add(new TypeLink(type, workflow, Via.PRODUCED_BY, null));
            }
        });
        List<WorkflowDefinition> sorted = new ArrayList<>(workflows == null ? List.of() : workflows);
        sorted.removeIf(w -> w == null || w.metadata() == null || w.name() == null);
        sorted.sort(Comparator.comparing(WorkflowDefinition::name));
        for (WorkflowDefinition w : sorted) {
            for (ResourceInput in : resourceInputs(w)) {
                consumes.add(new TypeLink(in.type(), w.name(), Via.INPUT, in.field()));
                if (in.orCreateWith() != null) {
                    runs.add(new RunLink(w.name(), in.orCreateWith(), null, Via.OR_CREATE_WITH, in.field()));
                }
            }
            for (Step s : w.allSteps()) {
                switch (s.type()) {
                    case REGISTER -> text(s, "type").ifPresent(t -> produces.add(new TypeLink(t, w.name(), Via.REGISTER, s.id())));
                    case USE -> text(s, "type").ifPresent(t -> consumes.add(new TypeLink(t, w.name(), Via.USE, s.id())));
                    case RUN -> text(s, "workflow").ifPresent(child -> runs.add(new RunLink(w.name(), child, text(s, "version").orElse(null), Via.RUN, s.id())));
                    default -> {
                    }
                }
            }
        }
        return new Graph(List.copyOf(produces), List.copyOf(consumes), List.copyOf(runs), java.util.Collections.unmodifiableMap(declared), cycles(runs));
    }

    /** An input that points at the registry: its field, its type and the workflow that can build one. */
    public record ResourceInput(String field, String type, String orCreateWith) {
    }

    /** The top-level inputs marked {@code x-resource}, in schema order. */
    public static List<ResourceInput> resourceInputs(WorkflowDefinition def) {
        List<ResourceInput> out = new ArrayList<>();
        JsonNode props = def == null || def.inputSchema() == null ? null : def.inputSchema().get("properties");
        if (props == null || !props.isObject()) {
            return out;
        }
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode xr = e.getValue().get("x-resource");
            if (xr == null || !xr.isObject()) {
                continue;
            }
            String type = blankToNull(xr.path("type").asText(null));
            if (type == null) {
                continue;
            }
            out.add(new ResourceInput(e.getKey(), type, blankToNull(xr.path("orCreateWith").asText(null))));
        }
        return out;
    }

    /** The input field that names the workflow that can build it, or empty. */
    public static Optional<ResourceInput> resourceInput(WorkflowDefinition def, String field) {
        return resourceInputs(def).stream().filter(i -> i.field().equals(field)).findFirst();
    }

    /**
     * The cycles among runs, each listed once from its smallest name and closed (the first name
     * repeats at the end), sorted.
     */
    static List<List<String>> cycles(List<RunLink> runs) {
        Map<String, Set<String>> edges = new TreeMap<>();
        for (RunLink r : runs) {
            edges.computeIfAbsent(r.consumer(), k -> new TreeSet<>()).add(r.producer());
        }
        Set<List<String>> found = new LinkedHashSet<>();
        Set<String> done = new HashSet<>();
        for (String start : edges.keySet()) {
            visit(start, edges, new ArrayList<>(), new HashSet<>(), done, found);
        }
        List<List<String>> out = new ArrayList<>(found);
        out.sort(Comparator.comparing(c -> String.join(" ", c)));
        return List.copyOf(out);
    }

    private static void visit(String node, Map<String, Set<String>> edges, List<String> path, Set<String> onPath, Set<String> done,
            Set<List<String>> found) {
        if (onPath.contains(node)) {
            List<String> loop = new ArrayList<>(path.subList(path.indexOf(node), path.size()));
            found.add(normalized(loop));
            return;
        }
        if (done.contains(node)) {
            return;
        }
        path.add(node);
        onPath.add(node);
        for (String next : edges.getOrDefault(node, Set.of())) {
            visit(next, edges, path, onPath, done, found);
        }
        onPath.remove(node);
        path.remove(path.size() - 1);
        done.add(node);
    }

    /** The loop rotated to start at its smallest name, closed with that name again. */
    private static List<String> normalized(List<String> loop) {
        int min = 0;
        for (int i = 1; i < loop.size(); i++) {
            if (loop.get(i).compareTo(loop.get(min)) < 0) {
                min = i;
            }
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < loop.size(); i++) {
            out.add(loop.get((min + i) % loop.size()));
        }
        out.add(out.get(0));
        return List.copyOf(out);
    }

    /** The producer's version range a run step gets when it names none: the producer's current major. */
    public static String defaultRange(String producerVersion) {
        if (producerVersion == null || !VersionRules.isSemver(producerVersion)) {
            return null;
        }
        return "^" + Semver.parse(producerVersion).major();
    }

    /**
     * The definition as it is published: every run step that names no version gets its
     * producer's current major ({@link #defaultRange}), written right after {@code workflow} the
     * way an author would pin it. {@code currentVersion} answers a producer's current published
     * version; a producer it does not know stays unpinned (the checks report it). Returns
     * {@code def} itself when nothing needs a pin.
     */
    public static WorkflowDefinition pinRanges(WorkflowDefinition def, Function<String, Optional<String>> currentVersion) {
        Function<Step, Optional<String>> range = s -> text(s, "workflow").flatMap(currentVersion).map(DependencyRules::defaultRange);
        if (def.allSteps().stream().noneMatch(s -> unpinned(s) && range.apply(s).isPresent())) {
            return def;
        }
        // a fresh copy, so the caller's definition (and its step parameters) stay as they were
        WorkflowDefinition copy = WorkflowYaml.fromJson(WorkflowYaml.toJson(def).deepCopy());
        for (Step s : copy.allSteps()) {
            if (unpinned(s) && s.with() instanceof ObjectNode with) {
                range.apply(s).ifPresent(r -> insertAfter(with, "workflow", "version", r));
            }
        }
        return copy;
    }

    private static boolean unpinned(Step s) {
        return s.type() == StepType.RUN && text(s, "workflow").isPresent() && text(s, "version").isEmpty();
    }

    private static void insertAfter(ObjectNode node, String after, String key, String value) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
        node.removeAll();
        fields.forEach((k, v) -> {
            if (!k.equals(key)) {
                node.set(k, v);
                if (k.equals(after)) {
                    node.put(key, value);
                }
            }
        });
        if (!node.has(key)) {
            node.put(key, value);
        }
    }

    private static Optional<String> text(Step s, String field) {
        return Optional.ofNullable(blankToNull(s.withText(field)));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
