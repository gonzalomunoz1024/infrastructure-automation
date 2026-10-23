package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Small readers over the JSON the events and the state service carry. */
public final class Json {

    /** One mapper for documents and events: Java time as ISO text, unknown fields ignored, whole numbers as longs. */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    private Json() {
    }

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    /** The text of a field, null when absent, null or not text. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.isTextual() ? v.asText() : v.isValueNode() ? v.asText() : null;
    }

    /** An instant from an ISO field, null when absent or unreadable. */
    public static Instant instant(JsonNode node, String field) {
        String s = text(node, field);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static Long longValue(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || !v.canConvertToLong() ? null : v.asLong();
    }

    public static Boolean bool(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || !v.isBoolean() ? null : v.asBoolean();
    }

    /** A field that is an object, or null. */
    public static JsonNode objectOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v != null && v.isObject() ? v : null;
    }

    /** A scalar JSON value as the plain Java value a document stores: text, number or boolean; null otherwise. */
    public static Object scalar(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isIntegralNumber()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        return null;
    }
}
