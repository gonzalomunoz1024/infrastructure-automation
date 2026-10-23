package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A published resource type: the document the platform serves, with the overlay merged and its hash set. */
public final class ResourceTypeDef {

    private final ObjectNode document;

    public ResourceTypeDef(ObjectNode document) {
        this.document = document;
    }

    /** The ResourceType document as GET /api/v1/resource-types/{name} answers it. */
    public ObjectNode document() {
        return document.deepCopy();
    }

    public String name() {
        return document.path("name").asText();
    }

    public String plural() {
        return document.path("plural").asText();
    }

    public String owner() {
        return document.path("owner").asText(null);
    }

    public String service() {
        return document.path("service").asText();
    }

    public String visibility() {
        return document.path("visibility").asText("team");
    }

    public String hash() {
        return document.path("hash").asText();
    }

    public String producedBy() {
        return document.path("producedBy").asText(null);
    }

    public JsonNode schema() {
        return document.path("schema");
    }

    public List<String> states() {
        List<String> states = new ArrayList<>();
        for (JsonNode state : document.path("states")) {
            states.add(state.asText());
        }
        return states;
    }

    public boolean hasObserve() {
        return document.path("observe").isObject() && document.path("observe").hasNonNull("action");
    }

    /** The observe block, or a missing node when the type has none. */
    public JsonNode observe() {
        return document.path("observe");
    }

    public boolean hasDiscover() {
        return observe().path("discover").isObject();
    }

    public boolean hasList() {
        return observe().path("list").isObject();
    }

    public boolean hasMetrics() {
        return observe().path("metrics").isObject();
    }

    /** The schema fields marked sensitive by their x-console hints. */
    public Set<String> sensitiveFields() {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> property : schema().path("properties").properties()) {
            if (property.getValue().path("x-console").path("sensitive").asBoolean(false)) {
                names.add(property.getKey());
            }
        }
        return names;
    }

    /** The observed fields the type never compares. */
    public Set<String> ignoredFields() {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode name : observe().path("ignore")) {
            names.add(name.asText());
        }
        return names;
    }
}
