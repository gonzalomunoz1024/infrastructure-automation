package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Field-by-field differences between two JSON values, for the time-travel view. */
public final class JsonDiff {

    private JsonDiff() {
    }

    /** One changed leaf: {@code field} is a dotted path like {@code input.id} or {@code output.vm.name}. */
    public record Entry(String field, JsonNode before, JsonNode after) {
    }

    /** Every leaf of a value by dotted path; arrays index as {@code items[0]}. */
    public static Map<String, JsonNode> flatten(JsonNode value, String prefix) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        collect(value, prefix, out, 0);
        return out;
    }

    private static void collect(JsonNode v, String path, Map<String, JsonNode> out, int depth) {
        if (v == null || depth > 12) {
            return;
        }
        if (v.isObject() && v.size() > 0) {
            v.fields().forEachRemaining(e -> collect(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out, depth + 1));
        } else if (v.isArray() && v.size() > 0) {
            for (int i = 0; i < v.size(); i++) {
                collect(v.get(i), path + "[" + i + "]", out, depth + 1);
            }
        } else {
            out.put(path, v);
        }
    }

    public static List<Entry> diff(JsonNode before, JsonNode after, String prefix) {
        Map<String, JsonNode> b = flatten(before, prefix);
        Map<String, JsonNode> a = flatten(after, prefix);
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        List<Entry> out = new ArrayList<>();
        for (String k : keys) {
            JsonNode x = b.get(k);
            JsonNode y = a.get(k);
            if (x == null ? y != null && !y.isNull() : !x.equals(y)) {
                out.add(new Entry(k, x, y));
            }
        }
        return out;
    }
}
