package com.iaf.platform.runs.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Fill the top-level {@code default}s of an input schema into an input, so {@code $input.environment} reads {@code dev} when the caller left it out. */
public final class InputDefaults {

    private InputDefaults() {
    }

    public static JsonNode apply(JsonNode schema, JsonNode input) {
        ObjectNode out = input != null && input.isObject() ? ((ObjectNode) input).deepCopy() : JsonNodeFactory.instance.objectNode();
        if (schema == null || !schema.isObject()) {
            return out;
        }
        schema.path("properties").fields().forEachRemaining(e -> {
            JsonNode dflt = e.getValue().get("default");
            if (dflt != null && !out.has(e.getKey())) {
                out.set(e.getKey(), dflt);
            }
        });
        return out;
    }

    /** {@code defaults} under {@code item}: the item's fields win. */
    public static JsonNode merge(JsonNode defaults, JsonNode item) {
        ObjectNode out = defaults != null && defaults.isObject() ? ((ObjectNode) defaults).deepCopy() : JsonNodeFactory.instance.objectNode();
        if (item != null && item.isObject()) {
            out.setAll((ObjectNode) item);
        }
        return out;
    }
}
