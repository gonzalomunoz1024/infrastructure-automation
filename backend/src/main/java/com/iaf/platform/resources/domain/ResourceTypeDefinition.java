package com.iaf.platform.resources.domain;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.workflows.domain.WorkflowYaml;

/** One {@code kind: ResourceType} file: what a workflow can register and another can use. */
public record ResourceTypeDefinition(String name, String plural, String owner, JsonNode schema, List<String> states, String producedBy, String description) {

    public static final String KIND = "ResourceType";

    public static ResourceTypeDefinition parse(String yaml) {
        return fromJson(WorkflowYaml.readTree(yaml));
    }

    public static ResourceTypeDefinition fromJson(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("A resource type file is a YAML mapping");
        }
        String kind = root.path("kind").asText(null);
        if (kind != null && !KIND.equals(kind)) {
            throw new IllegalArgumentException("kind must be ResourceType, was " + kind);
        }
        JsonNode md = root.path("metadata");
        String name = md.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metadata.name is required");
        }
        List<String> states = new java.util.ArrayList<>();
        root.path("states").forEach(s -> states.add(s.asText()));
        if (states.isEmpty()) {
            states.addAll(List.of("building", "ready", "deprecated", "retired"));
        }
        return new ResourceTypeDefinition(name, md.path("plural").asText(name + "s"), md.path("owner").asText(null),
                root.get("schema"), states, root.path("producedBy").asText(null), md.path("description").asText(null));
    }

    public String readPath() {
        return "/api/v1/" + plural;
    }
}
