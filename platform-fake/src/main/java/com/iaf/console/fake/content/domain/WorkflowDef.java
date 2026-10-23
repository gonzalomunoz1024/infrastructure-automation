package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A published workflow as its YAML file describes it. */
public final class WorkflowDef {

    private final ObjectNode document;

    public WorkflowDef(ObjectNode document) {
        this.document = document;
    }

    public ObjectNode document() {
        return document.deepCopy();
    }

    private JsonNode metadata() {
        return document.path("metadata");
    }

    public String name() {
        return metadata().path("name").asText();
    }

    public String title() {
        return metadata().path("title").asText(name());
    }

    public String noun() {
        return metadata().path("noun").asText(null);
    }

    public String verb() {
        return metadata().path("verb").asText(null);
    }

    public String persona() {
        return metadata().path("persona").asText(null);
    }

    public String owner() {
        return metadata().path("owner").asText(null);
    }

    public String version() {
        return metadata().path("version").asText("0.0.0");
    }

    public String description() {
        return metadata().path("description").asText(null);
    }

    public List<String> tags() {
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : metadata().path("tags")) {
            tags.add(tag.asText());
        }
        return tags;
    }

    public JsonNode api() {
        return document.path("api");
    }

    public String method() {
        return api().path("method").asText("POST").toUpperCase(java.util.Locale.ROOT);
    }

    public String path() {
        return api().path("path").asText(null);
    }

    /** The JSON Schema of the input, an empty object schema when the file has none. */
    public JsonNode inputSchema() {
        JsonNode input = api().path("input");
        return input.isObject() ? input : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("type", "object");
    }

    public boolean preview() {
        return api().path("preview").asBoolean(false);
    }

    /** The api.batch block, or null when the workflow declares none. */
    public JsonNode batch() {
        JsonNode batch = api().path("batch");
        return batch.isObject() ? batch : null;
    }

    public JsonNode policy() {
        return document.path("policy");
    }

    public boolean approvalRequired() {
        return policy().path("approval").path("required").asBoolean(false);
    }

    public String approvalGroup() {
        return policy().path("approval").path("group").asText(null);
    }

    public boolean changeRequired() {
        return policy().path("change").path("required").asBoolean(false);
    }

    public List<String> rbac() {
        List<String> roles = new ArrayList<>();
        for (JsonNode role : policy().path("rbac")) {
            roles.add(role.asText());
        }
        return roles;
    }

    /** The top-level steps. */
    public ArrayNode steps() {
        JsonNode steps = document.path("workflow").path("do");
        return steps.isArray() ? (ArrayNode) steps : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    /** The workflow-level catch steps, empty when the workflow has none. */
    public ArrayNode catchSteps() {
        JsonNode steps = document.path("workflow").path("catch").path("do");
        return steps.isArray() ? (ArrayNode) steps : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
    }

    public JsonNode outputs() {
        return document.path("workflow").path("outputs");
    }

    /** The MCP tool name of the workflow: noun and verb with underscores. */
    public String mcpTool() {
        JsonNode explicit = document.path("mcp").path("toolName");
        if (explicit.isTextual()) {
            return explicit.asText();
        }
        if (noun() == null || verb() == null) {
            return name().replace('-', '_');
        }
        return (noun() + "_" + verb()).replace('-', '_');
    }

    /** The name of the generated route's operation, the mcp tool name in camel case. */
    public String operationId() {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : mcpTool().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }
}
