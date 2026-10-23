package com.iaf.console.fake.content.domain;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A provider as its YAML file describes it: its actions, each with an effect and a recorded sample. */
public final class ProviderDef {

    private final ObjectNode document;
    private final Map<String, JsonNode> actions = new LinkedHashMap<>();

    public ProviderDef(ObjectNode document) {
        this.document = document;
        for (JsonNode action : document.path("spec").path("actions")) {
            actions.put(action.path("id").asText(), action);
        }
    }

    public ObjectNode document() {
        return document.deepCopy();
    }

    public String name() {
        return document.path("metadata").path("name").asText();
    }

    public String title() {
        return document.path("metadata").path("title").asText(name());
    }

    public String owner() {
        return document.path("metadata").path("owner").asText(null);
    }

    public String version() {
        return document.path("metadata").path("version").asText("0.0.0");
    }

    public String type() {
        return document.path("spec").path("type").asText("http");
    }

    public Map<String, JsonNode> actions() {
        return actions;
    }

    /** An action by id, null when the provider has none of that id. */
    public JsonNode action(String id) {
        return actions.get(id);
    }

    public boolean hasAction(String id) {
        return actions.containsKey(id);
    }

    /** The effect of an action: read, create, change or delete; change when the file says nothing. */
    public String effect(String actionId) {
        JsonNode action = actions.get(actionId);
        return action == null ? "change" : action.path("effect").asText("change");
    }

    /** The recorded sample of an action, a missing node when it has none. */
    public JsonNode sample(String actionId) {
        JsonNode action = actions.get(actionId);
        return action == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : action.path("sample");
    }

    /** The action an Ansible playbook path runs, null when no action names the playbook. */
    public String actionForPlaybook(String playbookPath) {
        for (Map.Entry<String, JsonNode> action : actions.entrySet()) {
            if (playbookPath.equals(action.getValue().path("playbook").asText(null))) {
                return action.getKey();
            }
        }
        return null;
    }

    /** Adds the actions of an additions file (P-15 adds vm.update to vSphere). */
    public ProviderDef withActions(JsonNode additions) {
        ObjectNode merged = document.deepCopy();
        JsonNode spec = merged.path("spec");
        if (!spec.isObject()) {
            merged.set("spec", merged.objectNode());
        }
        ObjectNode specNode = (ObjectNode) merged.get("spec");
        if (!specNode.path("actions").isArray()) {
            specNode.set("actions", merged.arrayNode());
        }
        for (JsonNode action : additions) {
            if (!actions.containsKey(action.path("id").asText())) {
                ((com.fasterxml.jackson.databind.node.ArrayNode) specNode.get("actions")).add(action.deepCopy());
            }
        }
        return new ProviderDef(merged);
    }
}
