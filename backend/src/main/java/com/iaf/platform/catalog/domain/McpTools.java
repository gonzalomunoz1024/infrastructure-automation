package com.iaf.platform.catalog.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition.BatchPolicy;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * The MCP tool list at one moment: {@code <noun>_<verb>} and {@code <noun>_<verb>_batch} per
 * published workflow with {@code mcp.expose}, {@code list_<plural>} and {@code get_<plural>} per
 * resource type, and the fixed {@code get_job_status}, {@code cancel_job} and {@code draft_workflow}.
 */
public final class McpTools {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    public static final String GET_JOB_STATUS = "get_job_status";
    public static final String CANCEL_JOB = "cancel_job";
    public static final String DRAFT_WORKFLOW = "draft_workflow";
    public static final String BATCH_SUFFIX = "_batch";

    public enum Kind {
        INVOKE, BATCH, LIST_RESOURCES, GET_RESOURCE, JOB_STATUS, CANCEL_JOB, DRAFT_WORKFLOW
    }

    /** One tool as {@code tools/list} shows it, plus what it leads to. */
    public record Tool(String name, String title, String description, ObjectNode inputSchema, ObjectNode annotations,
            Kind kind, WorkflowRecord workflow, ResourceTypeDefinition type) {

        public ObjectNode toJson() {
            ObjectNode o = F.objectNode();
            o.put("name", name);
            if (title != null) {
                o.put("title", title);
            }
            if (description != null) {
                o.put("description", description);
            }
            o.set("inputSchema", inputSchema);
            if (annotations != null && !annotations.isEmpty()) {
                o.set("annotations", annotations);
            }
            return o;
        }
    }

    private McpTools() {
    }

    public static List<Tool> of(List<WorkflowRecord> published, List<ResourceTypeDefinition> types) {
        List<Tool> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        List<WorkflowRecord> workflows = new ArrayList<>(published == null ? List.of() : published);
        workflows.sort(Comparator.comparing(WorkflowRecord::name, Comparator.nullsLast(Comparator.naturalOrder())));
        for (WorkflowRecord w : workflows) {
            WorkflowDefinition d = w.definition();
            if (d == null || !d.mcpExposed()) {
                continue;
            }
            String name = d.mcpTool();
            if (name.isBlank() || "_".equals(name) || !names.add(name)) {
                continue;
            }
            String description = CatalogEntry.description(d);
            ObjectNode annotations = annotations(d.method().equals("GET"), d.method().equals("DELETE") || "delete".equals(d.verb()) || "destroy".equals(d.verb()));
            JsonNode input = CatalogEntry.input(d, published);
            out.add(new Tool(name, d.title(), description, inputSchema(input), annotations, Kind.INVOKE, w, null));
            String batchName = name + BATCH_SUFFIX;
            if (names.add(batchName)) {
                out.add(new Tool(batchName, d.title() + " (batch)",
                        "Run " + name + " for many items in waves. " + (description == null ? "" : description),
                        batchSchema(input, d.batchPolicy()), annotations, Kind.BATCH, w, null));
            }
        }
        List<ResourceTypeDefinition> ts = new ArrayList<>(types == null ? List.of() : types);
        ts.sort(Comparator.comparing(ResourceTypeDefinition::name, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ResourceTypeDefinition t : ts) {
            String plural = toolWord(t.plural());
            String listName = "list_" + plural;
            if (names.add(listName)) {
                out.add(new Tool(listName, "List " + t.plural(), "List the " + t.plural() + " in the resource registry" + describe(t),
                        listSchema(t), annotations(true, false), Kind.LIST_RESOURCES, null, t));
            }
            String getName = "get_" + plural;
            if (names.add(getName)) {
                out.add(new Tool(getName, "Get " + t.name(), "One " + t.name() + " from the resource registry, by id" + describe(t),
                        idSchema("id", "The " + t.name() + " id"), annotations(true, false), Kind.GET_RESOURCE, null, t));
            }
        }
        out.add(new Tool(GET_JOB_STATUS, "Get job status", "The status, steps, approvals and output of a job started by another tool",
                idSchema("jobId", "The job id a tool returned"), annotations(true, false), Kind.JOB_STATUS, null, null));
        out.add(new Tool(CANCEL_JOB, "Cancel job", "Cancel a queued or running job",
                idSchema("jobId", "The job id to cancel"), annotations(false, false), Kind.CANCEL_JOB, null, null));
        out.add(new Tool(DRAFT_WORKFLOW, "Draft workflow", "Ask the natural-language builder for a workflow draft (YAML, assumptions, questions) from a description",
                idSchema("description", "What the workflow should do, in plain words"), annotations(false, false), Kind.DRAFT_WORKFLOW, null, null));
        return List.copyOf(out);
    }

    public static Optional<Tool> find(List<Tool> tools, String name) {
        if (name == null) {
            return Optional.empty();
        }
        return tools.stream().filter(t -> t.name().equals(name)).findFirst();
    }

    /** {@code mongodb-clusters} -> {@code mongodb_clusters}. */
    public static String toolWord(String s) {
        return Ids.slug(s).replace('-', '_');
    }

    public static ArrayNode toJson(List<Tool> tools) {
        ArrayNode arr = F.arrayNode();
        tools.forEach(t -> arr.add(t.toJson()));
        return arr;
    }

    // ---------------------------------------------------------------- schemas

    /** The input schema of a workflow, or an open object when the file declares none. */
    public static ObjectNode inputSchema(JsonNode input) {
        if (input != null && input.isObject()) {
            ObjectNode copy = input.deepCopy();
            if (!copy.has("type")) {
                copy.put("type", "object");
            }
            return copy;
        }
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        o.set("properties", F.objectNode());
        return o;
    }

    /** {@code { items: [input], defaults?, rollout? }} for a batch. */
    public static ObjectNode batchSchema(JsonNode input, BatchPolicy batch) {
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        o.putArray("required").add("items");
        ObjectNode props = o.putObject("properties");
        ObjectNode items = props.putObject("items");
        items.put("type", "array");
        items.put("description", "One input per row; the batch defaults are merged under each row");
        items.put("minItems", 1);
        if (batch != null && batch.max() > 0) {
            items.put("maxItems", batch.max());
        }
        items.set("items", inputSchema(input));
        ObjectNode defaults = props.putObject("defaults");
        defaults.put("type", "object");
        defaults.put("description", "Fields every row inherits unless it sets them itself");
        props.set("rollout", rolloutSchema(batch == null ? null : batch.rollout()));
        return o;
    }

    public static ObjectNode rolloutSchema(WorkflowDefinition.RolloutPolicy dflt) {
        ObjectNode r = F.objectNode();
        r.put("type", "object");
        r.put("description", "How the rows roll out: waves of growing percentages, a parallelism cap and a stop threshold");
        ObjectNode p = r.putObject("properties");
        ObjectNode waves = p.putObject("waves");
        waves.put("type", "array");
        waves.putObject("items").put("type", "integer");
        ObjectNode max = p.putObject("maxParallel");
        max.put("type", "integer");
        max.put("minimum", 1);
        ObjectNode stop = p.putObject("stopIfFailedPercent");
        stop.put("type", "integer");
        stop.put("minimum", 0);
        stop.put("maximum", 100);
        if (dflt != null) {
            ArrayNode w = waves.putArray("default");
            dflt.waves().forEach(w::add);
            max.put("default", dflt.maxParallel());
            stop.put("default", dflt.stopIfFailedPercent());
        }
        return r;
    }

    public static ObjectNode listSchema(ResourceTypeDefinition t) {
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        ObjectNode props = o.putObject("properties");
        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "Only resources in this state");
        if (t.states() != null && !t.states().isEmpty()) {
            ArrayNode e = status.putArray("enum");
            t.states().forEach(e::add);
        }
        ObjectNode owner = props.putObject("owner");
        owner.put("type", "string");
        owner.put("description", "Only resources owned by this team");
        return o;
    }

    public static ObjectNode idSchema(String field, String description) {
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        o.putArray("required").add(field);
        ObjectNode f = o.putObject("properties").putObject(field);
        f.put("type", "string");
        f.put("description", description);
        return o;
    }

    private static ObjectNode annotations(boolean readOnly, boolean destructive) {
        ObjectNode a = F.objectNode();
        a.put("readOnlyHint", readOnly);
        a.put("destructiveHint", destructive);
        return a;
    }

    private static String describe(ResourceTypeDefinition t) {
        return t.description() == null || t.description().isBlank() ? "" : ". " + t.description();
    }
}
