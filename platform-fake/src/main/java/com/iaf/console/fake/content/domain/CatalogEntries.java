package com.iaf.console.fake.content.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The catalog entry of a workflow, with P-08's subject input, destructive and approval flags,
 * mutable fields, bulk flag and effect, and the generated preview and batch paths.
 */
public final class CatalogEntries {

    private static final List<String> EFFECT_ORDER = List.of("read", "change", "create", "delete");

    private CatalogEntries() {
    }

    /** The entry of a workflow; {@code effectOf} answers a provider action's effect. */
    public static ObjectNode entry(WorkflowDef workflow, BiFunction<String, String, String> effectOf) {
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        out.put("workflow", workflow.name());
        out.put("title", workflow.title());
        put(out, "noun", workflow.noun());
        put(out, "verb", workflow.verb());
        put(out, "persona", workflow.persona());
        out.put("method", workflow.method());
        put(out, "path", workflow.path());
        put(out, "batchPath", batchPath(workflow));
        put(out, "previewPath", previewPath(workflow));
        out.set("input", workflow.inputSchema().deepCopy());
        out.put("mcpTool", workflow.mcpTool());
        put(out, "owner", workflow.owner());
        out.put("version", workflow.version());
        put(out, "description", workflow.description());
        JsonNode batch = workflow.batch();
        if (batch != null) {
            ObjectNode spec = out.putObject("batch");
            if (batch.hasNonNull("max")) {
                spec.put("max", batch.path("max").asInt());
            }
            if (batch.path("rollout").isObject()) {
                spec.set("rollout", batch.path("rollout").deepCopy());
            }
        }
        put(out, "subjectInput", subjectInput(workflow));
        out.put("destructive", destructive(workflow));
        out.put("approval", workflow.approvalRequired());
        ArrayNode mutable = out.putArray("mutableFields");
        mutableFields(workflow).forEach(mutable::add);
        out.put("bulk", batch != null);
        out.put("effect", effect(workflow, effectOf));
        return out;
    }

    /** The preview route of a workflow with a preview, null otherwise. */
    public static String previewPath(WorkflowDef workflow) {
        String path = workflow.path();
        if (!workflow.preview() || path == null) {
            return null;
        }
        String method = workflow.method();
        boolean parameterPath = path.endsWith("}");
        if (parameterPath && !method.equals("POST") && !method.equals("DELETE")) {
            return path + ":" + workflow.verb() + ":preview";
        }
        return path + ":preview";
    }

    /** The batch route of a workflow: the collection path with the verb for routes that name one resource. */
    public static String batchPath(WorkflowDef workflow) {
        String path = workflow.path();
        if (path == null) {
            return null;
        }
        int parameter = path.indexOf("/{");
        if (parameter > 0) {
            return path.substring(0, parameter) + ":" + workflow.verb() + ":batch";
        }
        return path + ":batch";
    }

    /** The one input whose x-resource type is the noun, for every verb but create. */
    public static String subjectInput(WorkflowDef workflow) {
        if ("create".equals(workflow.verb()) || workflow.noun() == null) {
            return null;
        }
        JsonNode explicit = workflow.api().path("subject");
        if (explicit.isTextual()) {
            return explicit.asText();
        }
        String found = null;
        for (Map.Entry<String, JsonNode> property : workflow.inputSchema().path("properties").properties()) {
            if (workflow.noun().equals(property.getValue().path("x-resource").path("type").asText(null))) {
                if (found != null) {
                    return null;
                }
                found = property.getKey();
            }
        }
        return found;
    }

    public static boolean destructive(WorkflowDef workflow) {
        return "delete".equals(workflow.verb()) || workflow.tags().contains("destructive");
    }

    /** For an update workflow, the inputs marked mutable. */
    public static List<String> mutableFields(WorkflowDef workflow) {
        List<String> fields = new ArrayList<>();
        if (!"update".equals(workflow.verb())) {
            return fields;
        }
        for (Map.Entry<String, JsonNode> property : workflow.inputSchema().path("properties").properties()) {
            if (property.getValue().path("x-console").path("mutable").asBoolean(false)) {
                fields.add(property.getKey());
            }
        }
        return fields;
    }

    /** The strongest effect among the workflow's provider calls, compensations left out. */
    public static String effect(WorkflowDef workflow, BiFunction<String, String, String> effectOf) {
        String strongest = "read";
        for (String effect : effects(workflow.steps(), effectOf)) {
            if (EFFECT_ORDER.indexOf(effect) > EFFECT_ORDER.indexOf(strongest)) {
                strongest = effect;
            }
        }
        return strongest;
    }

    private static List<String> effects(JsonNode steps, BiFunction<String, String, String> effectOf) {
        List<String> effects = new ArrayList<>();
        for (JsonNode step : steps) {
            for (Map.Entry<String, JsonNode> entry : step.properties()) {
                JsonNode body = entry.getValue();
                if ("provider".equals(body.path("call").asText(null))) {
                    String provider = body.path("with").path("provider").asText();
                    String action = body.path("with").path("action").asText();
                    effects.add(effectOf.apply(provider, action));
                } else if (body.has("call")) {
                    effects.add(body.path("call").asText().equals("terraform") && !"apply".equals(body.path("with").path("action").asText()) ? "read" : "change");
                }
                if (body.has("register")) {
                    effects.add("retired".equals(body.path("register").path("state").asText(null)) ? "delete" : "create");
                }
                for (JsonNode nested : nestedSteps(body)) {
                    effects.addAll(effects(nested, effectOf));
                }
            }
        }
        return effects;
    }

    /** The nested step lists of a step body: switch cases, try/catch, fork branches and for loops. */
    public static List<JsonNode> nestedSteps(JsonNode body) {
        List<JsonNode> lists = new ArrayList<>();
        for (JsonNode branch : body.path("switch")) {
            JsonNode c = branch.has("case") ? branch.get("case") : branch.get("default");
            if (c != null && c.path("do").isArray()) {
                lists.add(c.get("do"));
            }
        }
        if (body.path("try").path("do").isArray()) {
            lists.add(body.path("try").get("do"));
        }
        if (body.path("try").path("catch").path("do").isArray()) {
            lists.add(body.path("try").path("catch").get("do"));
        }
        for (JsonNode branch : body.path("fork").path("branches")) {
            if (branch.isArray()) {
                lists.add(branch);
            }
        }
        if (body.path("for").path("do").isArray()) {
            lists.add(body.path("for").get("do"));
        }
        return lists;
    }

    private static void put(ObjectNode out, String field, String value) {
        if (value != null) {
            out.put(field, value);
        }
    }
}
