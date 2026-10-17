package com.iaf.platform.catalog.domain;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds a job input from the pieces a request carries: a JSON body (or the query string for a
 * read), the path parameters and, for a batch row, the batch defaults under the row. Path and
 * query values arrive as text, so they are coerced to the type the input schema declares.
 */
public final class Inputs {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private Inputs() {
    }

    /** {@code base} with {@code over} on top (shallow: the job input is one level of fields). */
    public static ObjectNode merge(JsonNode base, JsonNode over) {
        ObjectNode out = base != null && base.isObject() ? ((ObjectNode) base).deepCopy() : F.objectNode();
        if (over != null && over.isObject()) {
            out.setAll((ObjectNode) over);
        }
        return out;
    }

    /** The input of one call: the body (an object or nothing) with the path parameters on top. */
    public static ObjectNode of(JsonNode body, Map<String, String> params, JsonNode schema) {
        if (body != null && !body.isNull() && !body.isMissingNode() && !body.isObject()) {
            throw new IllegalArgumentException("The request body must be a JSON object");
        }
        ObjectNode out = merge(body, null);
        if (params != null) {
            params.forEach((k, v) -> out.set(k, coerce(schema, k, v)));
        }
        return out;
    }

    /** A text value as the schema's type for that property: integer, number, boolean or string. */
    public static JsonNode coerce(JsonNode schema, String field, String value) {
        if (value == null) {
            return F.nullNode();
        }
        String type = schema == null ? null : schema.path("properties").path(field).path("type").asText(null);
        if (type == null) {
            return F.textNode(value);
        }
        try {
            return switch (type) {
                case "integer" -> F.numberNode(Long.parseLong(value.trim()));
                case "number" -> F.numberNode(Double.parseDouble(value.trim()));
                case "boolean" -> "true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim())
                        ? F.booleanNode(Boolean.parseBoolean(value.trim()))
                        : F.textNode(value);
                default -> F.textNode(value);
            };
        } catch (NumberFormatException e) {
            return F.textNode(value);
        }
    }

    /** Query parameters (first value each) as an object, coerced like path parameters. */
    public static ObjectNode ofQuery(Map<String, String> query, JsonNode schema) {
        ObjectNode out = F.objectNode();
        if (query != null) {
            query.forEach((k, v) -> out.set(k, coerce(schema, k, v)));
        }
        return out;
    }
}
