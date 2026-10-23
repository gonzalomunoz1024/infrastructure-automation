package com.iaf.console.state.inventory.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The one JSON mapper of the domain: instants as ISO text, nulls left out, unknown fields ignored. */
public final class Json {

    /** A JSON object as a map, for {@code convertValue}. */
    public static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
            .build();

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** A value as a JSON node, null as null. */
    public static JsonNode node(Object value) {
        return value == null ? null : MAPPER.valueToTree(value);
    }

    /** A value as an object node; null stays null, anything that is not an object is an error. */
    public static ObjectNode objectNode(Object value) {
        if (value == null) {
            return null;
        }
        JsonNode n = MAPPER.valueToTree(value);
        if (!(n instanceof ObjectNode o)) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return o;
    }

    /** An object node, or an empty one for null. */
    public static ObjectNode orEmpty(ObjectNode node) {
        return node == null ? object() : node;
    }

    public static ObjectNode copy(ObjectNode node) {
        return node == null ? null : node.deepCopy();
    }

    /** The text fields of an object as a map, in field order; non-text values as their JSON text. */
    public static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                out.put(e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : e.getValue().toString());
            }
        }
        return out;
    }

    /** Sets a field unless the value is null. */
    public static ObjectNode putIf(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
        return node;
    }

    public static ObjectNode setIf(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull()) {
            node.set(field, value);
        }
        return node;
    }

    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public static ObjectNode objectField(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v instanceof ObjectNode o ? o : null;
    }
}
