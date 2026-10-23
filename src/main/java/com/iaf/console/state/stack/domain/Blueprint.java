package com.iaf.console.state.stack.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A blueprint (hub decision 33, {@code blueprints/<name>.yaml}) as the state service runs it: its
 * metadata, parameters, resources in the file's order (each with every resource it waits for) and
 * outputs. {@link BlueprintParser} makes one and checks it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Blueprint(
        String name,
        String version,
        String title,
        String description,
        String owner,
        List<String> tags,
        Map<String, BlueprintParameter> parameters,
        Map<String, BlueprintResource> resources,
        Map<String, BlueprintOutput> outputs) {

    public Blueprint {
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        resources = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        outputs = outputs == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    /** The resource ids by depth: wave n holds the resources whose longest chain of dependencies is n long. */
    public List<List<String>> waves() {
        Map<String, Integer> depth = new HashMap<>();
        for (String id : resources.keySet()) {
            depthOf(id, depth);
        }
        TreeMap<Integer, List<String>> byDepth = new TreeMap<>();
        for (String id : resources.keySet()) {
            byDepth.computeIfAbsent(depth.get(id), d -> new ArrayList<>()).add(id);
        }
        return List.copyOf(byDepth.values());
    }

    public int waveOf(String id) {
        return depthOf(id, new HashMap<>());
    }

    private int depthOf(String id, Map<String, Integer> depth) {
        Integer known = depth.get(id);
        if (known != null) {
            return known;
        }
        int d = 0;
        for (String dep : resources.get(id).dependsOn()) {
            d = Math.max(d, depthOf(dep, depth) + 1);
        }
        depth.put(id, d);
        return d;
    }

    /** The resources that wait for this one. */
    public List<String> dependentsOf(String id) {
        List<String> out = new ArrayList<>();
        resources.forEach((other, r) -> {
            if (r.dependsOn().contains(id)) {
                out.add(other);
            }
        });
        return out;
    }

    /** How many resources of each type a stack of it creates. */
    public Map<String, Integer> creates() {
        Map<String, Integer> out = new TreeMap<>();
        resources.values().forEach(r -> out.merge(r.type(), 1, Integer::sum));
        return out;
    }
}
