package com.iaf.console.platform.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** A kind: ResourceType as the platform publishes it, with P-06's service, console and observe blocks. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceType(
        String name,
        String plural,
        String owner,
        String description,
        String visibility,
        JsonNode schema,
        List<String> states,
        String producedBy,
        String service,
        ConsoleBlock console,
        ObserveSpec observe,
        Integer version,
        String hash) {

    /** The x-console hints of a schema field, empty when it has none. */
    public JsonNode consoleHints(String field) {
        if (schema == null) {
            return null;
        }
        JsonNode property = schema.path("properties").path(field);
        return property.has("x-console") ? property.get("x-console") : null;
    }

    /** The fields of the schema, in file order. */
    public List<String> fields() {
        List<String> names = new ArrayList<>();
        if (schema != null) {
            schema.path("properties").fieldNames().forEachRemaining(names::add);
        }
        return names;
    }
}
