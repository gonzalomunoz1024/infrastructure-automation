package com.iaf.platform.workflows.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.workflows.domain.DependencyRules.ResourceInput;

/**
 * "Build it first": an input marked {@code x-resource} with {@code orCreateWith: <workflow>}
 * takes the id of a resource in the registry, or {@code { create: { ...that workflow's input } }}
 * to have one built. The schema callers see shows both forms; a start judges the request against
 * the producer's published input; the interpreter runs the producer as a child job first and
 * puts the id of the resource it registered in place of the request.
 */
public final class CreateRequests {

    /** The key of a request to build the resource first. */
    public static final String CREATE = "create";

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    /** The keywords that describe a field rather than constrain it; they stay on the field when its forms split. */
    private static final Set<String> ANNOTATIONS = Set.of("title", "description", "default", "examples", "deprecated", "readOnly", "writeOnly", "$comment");

    private CreateRequests() {
    }

    /** Whether an input value asks for a new resource rather than naming one. */
    public static boolean asksToCreate(JsonNode value) {
        return value != null && value.isObject() && value.has(CREATE);
    }

    /** The {@code orCreateWith} inputs of {@code def} whose value in {@code input} asks for a new resource, in schema order. */
    public static List<ResourceInput> of(WorkflowDefinition def, JsonNode input) {
        if (input == null || !input.isObject()) {
            return List.of();
        }
        return DependencyRules.resourceInputs(def).stream().filter(r -> r.orCreateWith() != null && asksToCreate(input.get(r.field()))).toList();
    }

    /**
     * The schema problems of an input, with every create request let through: those are judged
     * against their producers' inputs by {@link #problems}. String ids are judged as written.
     */
    public static List<Problem> inputProblems(WorkflowDefinition def, JsonNode input) {
        return Schemas.validate(open(def.inputSchema(), of(def, input).stream().map(ResourceInput::field).toList()), input);
    }

    /** The schema with the given properties accepting anything. */
    static JsonNode open(JsonNode schema, Collection<String> fields) {
        if (fields.isEmpty() || schema == null || !schema.isObject() || !schema.path("properties").isObject()) {
            return schema;
        }
        ObjectNode copy = schema.deepCopy();
        ObjectNode props = (ObjectNode) copy.get("properties");
        fields.forEach(f -> {
            if (props.has(f)) {
                props.set(f, F.objectNode());
            }
        });
        return copy;
    }

    /**
     * Each create request against the published input of its producer, with the producer's
     * defaults filled in first; the field of a problem is {@code <input>.create.<field>}. A
     * request for a producer with no published version, or anything but {@code create} in the
     * request, is a problem too.
     */
    public static List<Problem> problems(WorkflowDefinition def, JsonNode input, Function<String, Optional<WorkflowDefinition>> producers) {
        List<Problem> out = new ArrayList<>();
        for (ResourceInput r : of(def, input)) {
            JsonNode request = input.get(r.field());
            Iterator<String> keys = request.fieldNames();
            while (keys.hasNext()) {
                String k = keys.next();
                if (!CREATE.equals(k)) {
                    out.add(Problem.error("schema", null, r.field() + "." + k, "A request to build the " + r.type() + " holds only create"));
                }
            }
            Optional<WorkflowDefinition> producer = producers.apply(r.orCreateWith());
            if (producer.isEmpty()) {
                out.add(Problem.error("input.unknownProducer", null, r.field(), "No published version of " + r.orCreateWith() + " to build the " + r.type() + " with"));
                continue;
            }
            JsonNode create = request.get(CREATE);
            if (!create.isObject()) {
                out.add(Problem.error("schema", null, r.field() + "." + CREATE, "create is the input of " + r.orCreateWith() + ", an object"));
                continue;
            }
            JsonNode schema = producer.get().inputSchema();
            for (Problem p : Schemas.validate(schema, withDefaults(schema, create))) {
                String at = r.field() + "." + CREATE + (p.field() == null ? "" : "." + p.field());
                out.add(new Problem(p.level(), p.code(), p.step(), at, p.message(), p.fix()));
            }
        }
        return out;
    }

    /** The producer's top-level defaults under the request, as its own start would fill them. */
    static JsonNode withDefaults(JsonNode schema, JsonNode create) {
        ObjectNode out = create.deepCopy();
        JsonNode props = schema == null ? null : schema.get("properties");
        if (props != null && props.isObject()) {
            props.properties().forEach(e -> {
                JsonNode dflt = e.getValue().get("default");
                if (dflt != null && !out.has(e.getKey())) {
                    out.set(e.getKey(), dflt);
                }
            });
        }
        return out;
    }

    /**
     * The input schema as callers see it (catalog, OpenAPI, MCP): every {@code orCreateWith}
     * field keeps its title, description and {@code x-resource}, and takes {@code anyOf} the id
     * as written or {@code { create: <the producer's input schema> }}. {@code producerInput}
     * answers a producer's published input schema; a field whose producer it does not know stays
     * as written.
     */
    public static JsonNode withCreateForms(JsonNode input, Function<String, Optional<JsonNode>> producerInput) {
        if (input == null || !input.isObject() || !input.path("properties").isObject()) {
            return input;
        }
        ObjectNode copy = null;
        Iterator<Map.Entry<String, JsonNode>> it = input.get("properties").properties().iterator();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String producer = e.getValue().path("x-resource").path("orCreateWith").asText(null);
            Optional<JsonNode> schema = producer == null || producer.isBlank() ? Optional.empty() : producerInput.apply(producer);
            if (schema.isEmpty()) {
                continue;
            }
            if (copy == null) {
                copy = input.deepCopy();
            }
            ((ObjectNode) copy.get("properties")).set(e.getKey(), bothForms(e.getValue(), producer, schema.get()));
        }
        return copy == null ? input : copy;
    }

    private static ObjectNode bothForms(JsonNode field, String producer, JsonNode producerInput) {
        ObjectNode out = F.objectNode();
        ObjectNode id = F.objectNode();
        field.properties().forEach(e -> {
            if (ANNOTATIONS.contains(e.getKey()) || e.getKey().startsWith("x-")) {
                out.set(e.getKey(), e.getValue());
            } else {
                id.set(e.getKey(), e.getValue());
            }
        });
        String type = field.path("x-resource").path("type").asText("resource");
        if (!id.has("type")) {
            id.put("type", "string");
        }
        id.put("description", "An existing " + type + " id from the registry");
        ObjectNode build = F.objectNode();
        build.put("type", "object");
        build.put("description", "Build a new " + type + " with " + producer + " first; the job continues with its id");
        build.putArray("required").add(CREATE);
        build.put("additionalProperties", false);
        ObjectNode create = producerInput != null && producerInput.isObject() ? producerInput.deepCopy() : F.objectNode();
        if (!create.has("type")) {
            create.put("type", "object");
        }
        build.putObject("properties").set(CREATE, create);
        out.putArray("anyOf").add(id).add(build);
        return out;
    }
}
