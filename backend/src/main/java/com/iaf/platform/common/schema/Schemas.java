package com.iaf.platform.common.schema;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * JSON Schema helpers used by the gateway (input validation), the checks (which fields a step
 * returns), the wizard (schemas inferred from real responses) and the catalog form.
 */
public final class Schemas {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Schemas() {
    }

    /** Validate data against a schema; one problem per violation, field = the JSON pointer. */
    public static List<Problem> validate(JsonNode schema, JsonNode data) {
        List<Problem> problems = new ArrayList<>();
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return problems;
        }
        JsonSchema js = FACTORY.getSchema(schema);
        Set<ValidationMessage> messages = js.validate(data == null ? F.objectNode() : data);
        for (ValidationMessage m : messages) {
            String field = m.getInstanceLocation() == null ? null : m.getInstanceLocation().toString();
            if (field != null && field.startsWith("$.")) {
                field = field.substring(2);
            } else if ("$".equals(field)) {
                field = null;
            }
            problems.add(Problem.error("schema", null, field, m.getMessage().replaceFirst("^\\$\\.?", "").trim()));
        }
        return problems;
    }

    public static boolean isValid(JsonNode schema, JsonNode data) {
        return validate(schema, data).isEmpty();
    }

    /** Infer a schema from an example value (the HTTP-without-a-spec and overrides paths). */
    public static ObjectNode infer(JsonNode example) {
        ObjectNode s = F.objectNode();
        if (example == null || example.isNull()) {
            s.put("type", "null");
            return s;
        }
        if (example.isObject()) {
            s.put("type", "object");
            ObjectNode props = F.objectNode();
            example.properties().forEach(e -> props.set(e.getKey(), infer(e.getValue())));
            s.set("properties", props);
            return s;
        }
        if (example.isArray()) {
            s.put("type", "array");
            if (example.size() > 0) {
                s.set("items", infer(example.get(0)));
            }
            return s;
        }
        if (example.isBoolean()) {
            s.put("type", "boolean");
        } else if (example.isIntegralNumber()) {
            s.put("type", "integer");
        } else if (example.isNumber()) {
            s.put("type", "number");
        } else {
            s.put("type", "string");
        }
        return s;
    }

    /**
     * Every dotted leaf (and container) path a schema describes: {@code vm.id}, {@code vm.name},
     * {@code items}. Used by the did-you-mean check and the mapping panel.
     */
    public static List<String> paths(JsonNode schema) {
        List<String> out = new ArrayList<>();
        collectPaths(schema, "", out, 0);
        return out;
    }

    private static void collectPaths(JsonNode schema, String prefix, List<String> out, int depth) {
        if (schema == null || !schema.isObject() || depth > 8) {
            return;
        }
        JsonNode props = schema.get("properties");
        if (props != null && props.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = props.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                out.add(p);
                collectPaths(e.getValue(), p, out, depth + 1);
            }
        }
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            collectPaths(items, prefix.isEmpty() ? "[]" : prefix + "[]", out, depth + 1);
        }
    }

    /** Every dotted path present in an example value (same shape as {@link #paths}). */
    public static List<String> pathsOf(JsonNode example) {
        List<String> out = new ArrayList<>();
        collectValuePaths(example, "", out, 0);
        return out;
    }

    private static void collectValuePaths(JsonNode v, String prefix, List<String> out, int depth) {
        if (v == null || depth > 8) {
            return;
        }
        if (v.isObject()) {
            v.properties().forEach(e -> {
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                out.add(p);
                collectValuePaths(e.getValue(), p, out, depth + 1);
            });
        } else if (v.isArray() && v.size() > 0) {
            collectValuePaths(v.get(0), prefix.isEmpty() ? "[]" : prefix + "[]", out, depth + 1);
        }
    }

    /**
     * Compare a real response with a schema: fields the response has that the schema lacks, and
     * fields whose type differs. Powers "Use the real response" in the wizard.
     */
    public static List<Mismatch> mismatches(JsonNode schema, JsonNode response) {
        List<Mismatch> out = new ArrayList<>();
        compare(schema, response, "", out, 0);
        return out;
    }

    public record Mismatch(String field, String expected, String actual) {
    }

    private static void compare(JsonNode schema, JsonNode value, String prefix, List<Mismatch> out, int depth) {
        if (value == null || depth > 8) {
            return;
        }
        String actual = typeOf(value);
        if (schema == null || !schema.isObject() || schema.isEmpty()) {
            if (!prefix.isEmpty()) {
                out.add(new Mismatch(prefix, "missing", actual));
            }
            return;
        }
        String expected = schema.path("type").asText(null);
        if (expected != null && !expected.equals(actual) && !("number".equals(expected) && "integer".equals(actual))
                && !value.isNull()) {
            out.add(new Mismatch(prefix.isEmpty() ? "$" : prefix, expected, actual));
            return;
        }
        if (value.isObject()) {
            JsonNode props = schema.path("properties");
            value.properties().forEach(e -> {
                String p = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                JsonNode sub = props.get(e.getKey());
                if (sub == null) {
                    out.add(new Mismatch(p, "missing", typeOf(e.getValue())));
                } else {
                    compare(sub, e.getValue(), p, out, depth + 1);
                }
            });
        } else if (value.isArray() && value.size() > 0) {
            compare(schema.get("items"), value.get(0), prefix + "[]", out, depth + 1);
        }
    }

    private static String typeOf(JsonNode v) {
        if (v.isObject()) {
            return "object";
        }
        if (v.isArray()) {
            return "array";
        }
        if (v.isBoolean()) {
            return "boolean";
        }
        if (v.isIntegralNumber()) {
            return "integer";
        }
        if (v.isNumber()) {
            return "number";
        }
        if (v.isNull()) {
            return "null";
        }
        return "string";
    }

    /** Deep-merge an override block into a schema (overrides win, nothing is removed). */
    public static ObjectNode merge(JsonNode schema, JsonNode override) {
        ObjectNode out = schema != null && schema.isObject() ? schema.deepCopy() : F.objectNode();
        if (override == null || !override.isObject()) {
            return out;
        }
        override.properties().forEach(e -> {
            JsonNode existing = out.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) {
                out.set(e.getKey(), merge(existing, e.getValue()));
            } else {
                out.set(e.getKey(), e.getValue());
            }
        });
        return out;
    }

    /** A sample value that satisfies a schema, for forms, mocks and dry runs. */
    public static JsonNode sample(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return F.nullNode();
        }
        if (schema.has("example")) {
            return schema.get("example");
        }
        if (schema.has("default")) {
            return schema.get("default");
        }
        if (schema.has("enum") && schema.get("enum").size() > 0) {
            return schema.get("enum").get(0);
        }
        String type = schema.path("type").asText("object");
        return switch (type) {
            case "object" -> {
                ObjectNode o = F.objectNode();
                JsonNode props = schema.path("properties");
                props.properties().forEach(e -> o.set(e.getKey(), sample(e.getValue())));
                yield o;
            }
            case "array" -> {
                ArrayNode a = F.arrayNode();
                if (schema.has("items")) {
                    a.add(sample(schema.get("items")));
                }
                yield a;
            }
            case "integer" -> F.numberNode(1);
            case "number" -> F.numberNode(1.0);
            case "boolean" -> F.booleanNode(true);
            case "null" -> F.nullNode();
            default -> F.textNode(schema.path("format").asText("string").equals("date-time") ? "2026-01-01T00:00:00Z" : "string");
        };
    }

    /** Ordered copy of the top-level properties, for forms. */
    public static Map<String, JsonNode> properties(JsonNode schema) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (schema != null) {
            schema.path("properties").properties().forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static ObjectNode object() {
        return F.objectNode();
    }
}
