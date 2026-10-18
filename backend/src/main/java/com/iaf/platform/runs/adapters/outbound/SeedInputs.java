package com.iaf.platform.runs.adapters.outbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.runs.domain.InputDefaults;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

/**
 * Inputs for the seeded history, read from each workflow's input schema so no field name or
 * value lives in the code: an {@code x-resource} field gets the id of a ready resource of its
 * type from the registry, a field with {@code examples} one of them, an enum its default half the
 * time and otherwise any value, and a required field without either the schema's sample.
 * Optional fields without examples stay unset, as people leave them.
 */
final class SeedInputs {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /** Ready resource ids per type, as the registry holds them. */
    private final Map<String, List<String>> ids;

    SeedInputs(Map<String, List<String>> readyIdsByType) {
        this.ids = readyIdsByType == null ? Map.of() : readyIdsByType;
    }

    /** The first {@code x-resource} field of the input and its type, or null when it has none. */
    static String[] resourceField(WorkflowDefinition def) {
        JsonNode props = def.inputSchema() == null ? null : def.inputSchema().path("properties");
        if (props == null || !props.isObject()) {
            return null;
        }
        for (Map.Entry<String, JsonNode> e : props.properties()) {
            String type = e.getValue().path("x-resource").path("type").asText("");
            if (!type.isEmpty()) {
                return new String[] {e.getKey(), type};
            }
        }
        return null;
    }

    /** The ready ids of one resource type, in registry order. */
    List<String> idsOf(String type) {
        return ids.getOrDefault(type, List.of());
    }

    JsonNode inputFor(WorkflowDefinition def, Random rnd) {
        JsonNode schema = def.inputSchema();
        ObjectNode out = F.objectNode();
        if (schema == null || !schema.path("properties").isObject()) {
            return out;
        }
        List<String> required = new ArrayList<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        for (Map.Entry<String, JsonNode> e : schema.get("properties").properties()) {
            JsonNode value = valueFor(e.getValue(), required.contains(e.getKey()), rnd);
            if (value != null) {
                out.set(e.getKey(), value);
            }
        }
        return InputDefaults.apply(schema, out);
    }

    private JsonNode valueFor(JsonNode p, boolean required, Random rnd) {
        String resourceType = p.path("x-resource").path("type").asText("");
        if (!resourceType.isEmpty()) {
            List<String> ready = idsOf(resourceType);
            if (!ready.isEmpty()) {
                return F.textNode(ready.get(rnd.nextInt(ready.size())));
            }
            return required ? Schemas.sample(p) : null;
        }
        JsonNode examples = p.path("examples");
        if (examples.isArray() && !examples.isEmpty()) {
            return examples.get(rnd.nextInt(examples.size()));
        }
        JsonNode values = p.path("enum");
        if (values.isArray() && !values.isEmpty()) {
            if (p.has("default") && rnd.nextBoolean()) {
                return p.get("default");
            }
            return values.get(rnd.nextInt(values.size()));
        }
        if (p.has("default")) {
            return p.get("default");
        }
        return required ? Schemas.sample(p) : null;
    }
}
