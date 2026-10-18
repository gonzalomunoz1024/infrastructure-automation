package com.iaf.platform.runs.domain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a provider call that created, changed or deleted something acted on, for the "what
 * changed" evidence. Generic: the path parameters of the action's path ({@code /vms/{vmId}}) read
 * from the call's input; else an {@code id} or {@code name} (or a field ending in Id or Name) of
 * the input; else of the output, where a create usually answers with the new object's id.
 */
public final class ChangeTargets {

    private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}/]+)}");

    private ChangeTargets() {
    }

    public static String of(String actionPath, JsonNode input, JsonNode output) {
        if (actionPath != null) {
            List<String> values = new ArrayList<>();
            Matcher m = PATH_PARAM.matcher(actionPath);
            while (m.find()) {
                String v = scalar(input == null ? null : input.get(m.group(1)));
                if (v != null) {
                    values.add(v);
                }
            }
            if (!values.isEmpty()) {
                return String.join("/", values);
            }
        }
        String fromInput = identifier(input);
        return fromInput != null ? fromInput : identifier(output);
    }

    private static String identifier(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String key : List.of("id", "name")) {
            String v = scalar(node.get(key));
            if (v != null) {
                return v;
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            if (f.getKey().endsWith("Id") || f.getKey().endsWith("Name")) {
                String v = scalar(f.getValue());
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    private static String scalar(JsonNode v) {
        if (v == null || v.isNull() || v.isContainerNode()) {
            return null;
        }
        String s = v.asText();
        return s.isBlank() ? null : s;
    }
}
