package com.iaf.console.fake.content.domain;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builds the published ResourceType document from the platform's type file and the hub's overlay:
 * the overlay sets the service, the console and observe blocks and adds x-console hints and
 * fields to the schema (P-06, P-15). The service defaults to the owner team without its prefix.
 */
public final class TypeOverlays {

    private TypeOverlays() {
    }

    /** The published document, without its version and hash. */
    public static ObjectNode merge(JsonNode typeFile, JsonNode overlay) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        JsonNode metadata = typeFile.path("metadata");
        String name = metadata.path("name").asText();
        String owner = metadata.path("owner").asText(null);
        out.put("name", name);
        out.put("plural", metadata.path("plural").asText(name + "s"));
        if (owner != null) {
            out.put("owner", owner);
        }
        if (metadata.hasNonNull("description")) {
            out.put("description", metadata.path("description").asText());
        }
        out.put("visibility", typeFile.path("visibility").asText("team"));
        ObjectNode schema = typeFile.path("schema").isObject() ? typeFile.path("schema").deepCopy() : out.objectNode().put("type", "object");
        out.set("schema", schema);
        var states = out.putArray("states");
        for (JsonNode state : typeFile.path("states")) {
            states.add(state.asText());
        }
        if (typeFile.hasNonNull("producedBy")) {
            out.put("producedBy", typeFile.path("producedBy").asText());
        }
        String service = overlay == null ? null : overlay.path("metadata").path("service").asText(null);
        out.put("service", service != null ? service : defaultService(owner));
        if (overlay != null) {
            if (overlay.path("console").isObject()) {
                out.set("console", overlay.path("console").deepCopy());
            }
            if (overlay.path("observe").isObject()) {
                out.set("observe", overlay.path("observe").deepCopy());
            }
            if (overlay.path("cost").isObject()) {
                out.set("cost", overlay.path("cost").deepCopy());
            }
            mergeSchema(schema, overlay.path("schema"));
        }
        return out;
    }

    /** The service a type belongs to when the overlay says nothing: the owner team without its team- prefix. */
    public static String defaultService(String owner) {
        if (owner == null || owner.isBlank()) {
            return "platform";
        }
        return owner.startsWith("team-") ? owner.substring("team-".length()) : owner;
    }

    private static void mergeSchema(ObjectNode schema, JsonNode overlaySchema) {
        if (!overlaySchema.isObject()) {
            return;
        }
        JsonNode overlayProperties = overlaySchema.path("properties");
        if (overlayProperties.isObject()) {
            if (!schema.path("properties").isObject()) {
                schema.set("properties", schema.objectNode());
            }
            ObjectNode properties = (ObjectNode) schema.get("properties");
            for (Map.Entry<String, JsonNode> property : overlayProperties.properties()) {
                JsonNode existing = properties.get(property.getKey());
                if (existing != null && existing.isObject()) {
                    deepMerge((ObjectNode) existing, property.getValue());
                } else {
                    properties.set(property.getKey(), property.getValue().deepCopy());
                }
            }
        }
        for (Map.Entry<String, JsonNode> field : overlaySchema.properties()) {
            if (!field.getKey().equals("properties")) {
                schema.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }

    /** Sets every field of {@code from} on {@code into}, merging nested objects. */
    public static void deepMerge(ObjectNode into, JsonNode from) {
        for (Map.Entry<String, JsonNode> field : from.properties()) {
            JsonNode existing = into.get(field.getKey());
            if (existing != null && existing.isObject() && field.getValue().isObject()) {
                deepMerge((ObjectNode) existing, field.getValue());
            } else {
                into.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }
}
