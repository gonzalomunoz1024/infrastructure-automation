package com.iaf.console.bff.assistant.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A workflow's input form, its JSON Schema as the descriptors carry it (a create's whole input, a
 * verb's with the subject left out): what an assistant may fill and what is still missing. A
 * secret field (write-only, a password, marked {@code x-secret} or {@code x-sensitive}, or named
 * like a password, a token or a key) is never shown to an assistant and never filled by one.
 */
public final class InputForm {

    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(password|passwd|passphrase|secret|token|api[-_]?key|private[-_]?key|credential)");
    /** What of a field's schema an assistant sees. */
    private static final Set<String> SHOWN = Set.of("type", "title", "description", "enum", "default", "pattern", "minimum", "maximum", "minLength",
            "maxLength", "format", "examples", "x-resource", "items");
    private static final int MAX_TEXT = 1024;
    private static final int MAX_ITEMS = 100;

    private final JsonNode schema;

    private InputForm(JsonNode schema) {
        this.schema = schema;
    }

    /** The form of a schema; a null or non-object schema is a form without fields. */
    public static InputForm of(JsonNode schema) {
        return new InputForm(schema != null && schema.isObject() ? schema : JsonNodeFactory.instance.objectNode());
    }

    /** The form without one field, as a verb's form leaves out its subject; the same form when {@code field} is null. */
    public InputForm without(String field) {
        if (field == null || field.isBlank() || !schema.path("properties").has(field)) {
            return this;
        }
        ObjectNode copy = schema.deepCopy();
        ((ObjectNode) copy.get("properties")).remove(field);
        JsonNode required = copy.get("required");
        if (required != null && required.isArray()) {
            var kept = copy.putArray("required");
            for (JsonNode name : required) {
                if (!field.equals(name.asText())) {
                    kept.add(name);
                }
            }
        }
        return new InputForm(copy);
    }

    /** The form as an assistant sees it: the fields that are no secret, each with its type, title, enum, default and pattern; null without fields. */
    public JsonNode forAgent() {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject() || properties.isEmpty()) {
            return null;
        }
        ObjectNode shown = JsonNodeFactory.instance.objectNode();
        shown.put("type", "object");
        ObjectNode fields = shown.putObject("properties");
        for (Map.Entry<String, JsonNode> field : properties.properties()) {
            if (secret(field.getKey(), field.getValue())) {
                continue;
            }
            ObjectNode f = fields.putObject(field.getKey());
            if (field.getValue().isObject()) {
                for (Map.Entry<String, JsonNode> part : field.getValue().properties()) {
                    if (SHOWN.contains(part.getKey())) {
                        f.set(part.getKey(), part.getValue().deepCopy());
                    }
                }
            }
        }
        List<String> required = required();
        if (!required.isEmpty()) {
            var array = shown.putArray("required");
            required.forEach(array::add);
        }
        return shown;
    }

    /** The required fields that are no secret, in the schema's order. */
    public List<String> required() {
        List<String> required = new ArrayList<>();
        for (JsonNode name : schema.path("required")) {
            if (name.isTextual() && !secret(name.textValue(), schema.path("properties").path(name.textValue()))) {
                required.add(name.textValue());
            }
        }
        return required;
    }

    /**
     * The values of {@code input} that fit the form: fields it has, never a secret one, each of
     * the field's type, within its enum, pattern, length and range. What is left out is said in
     * {@code notes}.
     */
    public Map<String, Object> fill(Map<String, Object> input, List<String> notes) {
        Map<String, Object> kept = new LinkedHashMap<>();
        if (input == null) {
            return kept;
        }
        JsonNode properties = schema.path("properties");
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String name = entry.getKey();
            JsonNode field = properties.get(name);
            if (field == null) {
                notes.add("input '" + name + "' is no field of the form");
            } else if (secret(name, field)) {
                notes.add("input '" + name + "' is a secret field");
            } else {
                String problem = problem(field, entry.getValue());
                if (problem == null) {
                    kept.put(name, entry.getValue());
                } else {
                    notes.add("input '" + name + "' " + problem);
                }
            }
        }
        return kept;
    }

    /** The required fields that {@code filled} leaves empty and the form gives no default; secret ones are the person's to fill and left out. */
    public List<String> missing(Map<String, Object> filled) {
        List<String> missing = new ArrayList<>();
        for (String name : required()) {
            boolean defaulted = schema.path("properties").path(name).has("default");
            if ((filled == null || !filled.containsKey(name)) && !defaulted) {
                missing.add(name);
            }
        }
        return missing;
    }

    /** A copy of JSON without the fields named like a secret, at any depth: what an assistant is shown of a resource's attributes. */
    public static JsonNode redacted(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode copy = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!SECRET_NAME.matcher(field.getKey()).find()) {
                    JsonNode value = redacted(field.getValue());
                    copy.set(field.getKey(), value == null ? JsonNodeFactory.instance.nullNode() : value);
                }
            }
            return copy;
        }
        if (node.isArray()) {
            var copy = JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : node) {
                JsonNode value = redacted(item);
                copy.add(value == null ? JsonNodeFactory.instance.nullNode() : value);
            }
            return copy;
        }
        return node.deepCopy();
    }

    /** The values of a job's input an assistant may see: the form's fields that are no secret, and none named like one. */
    public Map<String, Object> shown(Map<String, Object> input) {
        Map<String, Object> shown = new LinkedHashMap<>();
        if (input == null) {
            return shown;
        }
        JsonNode properties = schema.path("properties");
        input.forEach((name, value) -> {
            JsonNode field = properties.get(name);
            if (value != null && field != null && !secret(name, field)) {
                shown.put(name, value);
            }
        });
        return shown;
    }

    /** Whether a field holds a secret. */
    public static boolean secret(String name, JsonNode field) {
        if (name != null && SECRET_NAME.matcher(name).find()) {
            return true;
        }
        if (field == null || !field.isObject()) {
            return false;
        }
        return field.path("writeOnly").asBoolean(false) || "password".equals(field.path("format").asText(null))
                || field.path("x-secret").asBoolean(false) || field.path("x-sensitive").asBoolean(false);
    }

    /** What is wrong with a value for a field; null when it fits. */
    private static String problem(JsonNode field, Object value) {
        String type = field.path("type").isTextual() ? field.path("type").textValue() : null;
        JsonNode allowed = field.get("enum");
        if (allowed != null && allowed.isArray() && !allowed.isEmpty()) {
            for (JsonNode option : allowed) {
                if (same(option, value)) {
                    return null;
                }
            }
            return "is not one of the field's values";
        }
        if (type == null) {
            return value instanceof String || value instanceof Number || value instanceof Boolean ? text(field, value) : "is not a plain value";
        }
        return switch (type) {
            case "string" -> value instanceof String ? text(field, value) : "is not text";
            case "integer" -> value instanceof Number n && whole(n) ? range(field, n) : "is not a whole number";
            case "number" -> value instanceof Number n ? range(field, n) : "is not a number";
            case "boolean" -> value instanceof Boolean ? null : "is not true or false";
            case "array" -> value instanceof List<?> l && l.size() <= MAX_ITEMS && l.stream().allMatch(InputForm::plain) ? null : "is not a short list of plain values";
            default -> "is of a type the copilot does not fill";
        };
    }

    private static String text(JsonNode field, Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        int max = field.path("maxLength").canConvertToInt() ? field.path("maxLength").intValue() : MAX_TEXT;
        if (s.length() > Math.min(max, MAX_TEXT)) {
            return "is too long";
        }
        if (field.path("minLength").canConvertToInt() && s.length() < field.path("minLength").intValue()) {
            return "is too short";
        }
        if (field.path("pattern").isTextual()) {
            try {
                if (!Pattern.compile(field.path("pattern").textValue()).matcher(s).find()) {
                    return "does not match the field's pattern";
                }
            } catch (PatternSyntaxException e) {
                return "cannot be checked against the field's pattern";
            }
        }
        return null;
    }

    private static String range(JsonNode field, Number n) {
        BigDecimal value = new BigDecimal(n.toString());
        if (field.path("minimum").isNumber() && value.compareTo(field.path("minimum").decimalValue()) < 0) {
            return "is below the field's minimum";
        }
        if (field.path("maximum").isNumber() && value.compareTo(field.path("maximum").decimalValue()) > 0) {
            return "is above the field's maximum";
        }
        return null;
    }

    private static boolean whole(Number n) {
        return n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || (n instanceof Double d && d == Math.rint(d) && !d.isInfinite());
    }

    private static boolean plain(Object item) {
        return item instanceof String || item instanceof Number || item instanceof Boolean;
    }

    private static boolean same(JsonNode option, Object value) {
        if (option.isTextual()) {
            return option.textValue().equals(value);
        }
        if (option.isNumber() && value instanceof Number n) {
            return option.decimalValue().compareTo(new BigDecimal(n.toString())) == 0;
        }
        if (option.isBoolean()) {
            return Boolean.valueOf(option.booleanValue()).equals(value);
        }
        return false;
    }
}
