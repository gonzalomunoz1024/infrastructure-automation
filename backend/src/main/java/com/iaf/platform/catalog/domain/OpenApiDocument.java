package com.iaf.platform.catalog.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.workflows.domain.WorkflowDefinition;
import com.iaf.platform.workflows.domain.WorkflowRecord;

/**
 * The OpenAPI 3.1 document of the generated surface: one path item per published workflow (plus
 * its {@code :batch} and {@code :preview} operations), the resource reads of every resource type,
 * the job reads, and the shared schemas. Built as Jackson nodes so it is served as it is.
 */
public final class OpenApiDocument {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final String JSON = "application/json";
    private static final String SCHEMAS = "#/components/schemas/";
    private static final String TAG_RESOURCES = "Resources";
    private static final String TAG_JOBS = "Jobs";

    private OpenApiDocument() {
    }

    public static ObjectNode build(List<WorkflowRecord> published, List<ResourceTypeDefinition> types) {
        ObjectNode doc = F.objectNode();
        doc.put("openapi", "3.1.0");
        ObjectNode info = doc.putObject("info");
        info.put("title", PlatformInfo.TITLE);
        info.put("version", PlatformInfo.VERSION);
        info.put("description", "Every published workflow as a REST operation (one job per call, 202 with a job id), "
                + "its batch and preview operations, and the resource registry reads. Generated from the live catalog.");
        ArrayNode servers = doc.putArray("servers");
        ObjectNode server = servers.addObject();
        server.put("url", "/");
        server.put("description", "This platform");

        ObjectNode paths = doc.putObject("paths");
        ObjectNode schemas = F.objectNode();
        Set<String> tags = new LinkedHashSet<>();

        List<WorkflowRecord> workflows = new ArrayList<>(published == null ? List.of() : published);
        workflows.sort(Comparator.comparing(WorkflowRecord::name, Comparator.nullsLast(Comparator.naturalOrder())));
        for (WorkflowRecord w : workflows) {
            WorkflowDefinition d = w.definition();
            if (d == null || !ReservedNouns.isGenerated(d.path())) {
                continue;
            }
            RouteTemplate route;
            try {
                route = RouteTemplate.compile(d.method(), d.path());
            } catch (IllegalArgumentException e) {
                continue;
            }
            String tag = d.metadata().folder() == null || d.metadata().folder().isBlank() ? "Workflows" : d.metadata().folder();
            tags.add(tag);
            workflow(paths, schemas, w, d, route, tag, published);
        }

        List<ResourceTypeDefinition> ts = new ArrayList<>(types == null ? List.of() : types);
        ts.sort(Comparator.comparing(ResourceTypeDefinition::plural, Comparator.nullsLast(Comparator.naturalOrder())));
        if (!ts.isEmpty()) {
            tags.add(TAG_RESOURCES);
        }
        for (ResourceTypeDefinition t : ts) {
            if (ReservedNouns.isGenerated(t.readPath())) {
                resourceType(paths, t);
            }
        }
        tags.add(TAG_JOBS);
        jobs(paths);

        ArrayNode tagList = doc.putArray("tags");
        for (String t : tags) {
            ObjectNode tag = tagList.addObject();
            tag.put("name", t);
            tag.put("description", TAG_RESOURCES.equals(t) ? "The resource registry: what jobs created"
                    : TAG_JOBS.equals(t) ? "Jobs started by the generated operations" : "Workflows in the " + t + " folder");
        }
        ObjectNode components = doc.putObject("components");
        components.set("schemas", sharedSchemas(schemas));
        return doc;
    }

    // ---------------------------------------------------------------- workflows

    private static void workflow(ObjectNode paths, ObjectNode schemas, WorkflowRecord w, WorkflowDefinition d, RouteTemplate route, String tag,
            List<WorkflowRecord> published) {
        String operationId = d.mcpTool();
        boolean read = "GET".equals(d.method());
        // a body field may also ask for its resource to be built first; a path or query parameter stays an id
        JsonNode ids = McpTools.inputSchema(d.inputSchema());
        JsonNode input = read ? ids : McpTools.inputSchema(CatalogEntry.input(d, published));
        schemas.set(operationId + "_input", input);
        String description = CatalogEntry.description(d);

        ObjectNode op = operation(operationId, d.title(), description, tag);
        extensions(op, w, d);
        ArrayNode params = op.putArray("parameters");
        pathParameters(params, route, ids);
        ObjectNode body = bodySchema(input, route.params());
        if (read) {
            queryParameters(params, body);
        } else {
            op.set("requestBody", requestBody(body, true));
        }
        ObjectNode responses = op.putObject("responses");
        responses.set("202", response("Job accepted", ref("JobAccepted")));
        responses.set("400", response("Input problems", ref("ProblemResponse")));
        responses.set("403", response("Not allowed: RBAC or policy", ref("ErrorResponse")));
        pathItem(paths, route.template()).set(d.method().toLowerCase(), op);

        ObjectNode batch = operation(operationId + "_batch", d.title() + " (batch)",
                "Run " + d.title() + " for many rows in waves. Every row is validated before anything starts.", tag);
        extensions(batch, w, d);
        RouteTemplate batchRoute = RouteTemplate.compile("POST", d.batchPath());
        ArrayNode batchParams = batch.putArray("parameters");
        pathParameters(batchParams, batchRoute, ids);
        batch.set("requestBody", requestBody(McpTools.batchSchema(bodySchema(input, batchRoute.params()), d.batchPolicy()), true));
        ObjectNode batchResponses = batch.putObject("responses");
        batchResponses.set("202", response("Batch accepted", ref("BatchAccepted")));
        batchResponses.set("400", response("Invalid rows", ref("BatchProblemResponse")));
        batchResponses.set("403", response("Not allowed: RBAC or policy", ref("ErrorResponse")));
        pathItem(paths, batchRoute.template()).set("post", batch);

        if (d.previewEnabled()) {
            ObjectNode preview = operation(operationId + "_preview", d.title() + " (preview)",
                    "The blast radius of a run before it happens: what each step would do, the resources it touches, cost and policy.", tag);
            extensions(preview, w, d);
            ArrayNode previewParams = preview.putArray("parameters");
            pathParameters(previewParams, route, input);
            preview.set("requestBody", requestBody(body, true));
            ObjectNode previewResponses = preview.putObject("responses");
            previewResponses.set("200", response("Preview", ref("Preview")));
            previewResponses.set("400", response("Input problems", ref("ProblemResponse")));
            previewResponses.set("403", response("Not allowed", ref("ErrorResponse")));
            pathItem(paths, route.template() + RouteTable.PREVIEW).set("post", preview);
        }
    }

    private static void extensions(ObjectNode op, WorkflowRecord w, WorkflowDefinition d) {
        op.put("x-workflow", d.name());
        op.put("x-version", w.version() == null ? d.version() : w.version());
        if (d.mcpExposed()) {
            op.put("x-mcp-tool", d.mcpTool());
        }
        if (d.policy() != null && d.policy().rbac() != null && !d.policy().rbac().isEmpty()) {
            ArrayNode rbac = op.putArray("x-rbac");
            d.policy().rbac().forEach(rbac::add);
        }
        if (d.policy() != null && d.policy().approval() != null && d.policy().approval().required()) {
            op.put("x-approval", d.policy().approval().group() == null ? "required" : d.policy().approval().group());
        }
    }

    private static void pathParameters(ArrayNode params, RouteTemplate route, JsonNode input) {
        for (String name : route.params()) {
            ObjectNode p = params.addObject();
            p.put("name", name);
            p.put("in", "path");
            p.put("required", true);
            JsonNode prop = input.path("properties").get(name);
            if (prop != null && prop.isObject()) {
                if (prop.has("description")) {
                    p.put("description", prop.get("description").asText());
                } else if (prop.has("title")) {
                    p.put("description", prop.get("title").asText());
                }
                p.set("schema", parameterSchema(prop));
            } else {
                p.putObject("schema").put("type", "string");
            }
        }
    }

    private static void queryParameters(ArrayNode params, ObjectNode body) {
        Set<String> required = new LinkedHashSet<>();
        body.path("required").forEach(r -> required.add(r.asText()));
        Iterator<Map.Entry<String, JsonNode>> it = body.path("properties").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            ObjectNode p = params.addObject();
            p.put("name", e.getKey());
            p.put("in", "query");
            p.put("required", required.contains(e.getKey()));
            if (e.getValue().has("description")) {
                p.put("description", e.getValue().get("description").asText());
            } else if (e.getValue().has("title")) {
                p.put("description", e.getValue().get("title").asText());
            }
            p.set("schema", parameterSchema(e.getValue()));
        }
    }

    /** A property schema as a parameter schema: the type and its constraints, without the form-only keys. */
    private static ObjectNode parameterSchema(JsonNode prop) {
        ObjectNode s = prop.deepCopy();
        s.remove("title");
        s.remove("description");
        return s;
    }

    /** The input schema without the fields that travel in the path. */
    static ObjectNode bodySchema(JsonNode input, List<String> pathParams) {
        ObjectNode s = input.deepCopy();
        if (pathParams.isEmpty()) {
            return s;
        }
        JsonNode props = s.get("properties");
        if (props != null && props.isObject()) {
            pathParams.forEach(((ObjectNode) props)::remove);
        }
        JsonNode required = s.get("required");
        if (required != null && required.isArray()) {
            ArrayNode kept = F.arrayNode();
            required.forEach(r -> {
                if (!pathParams.contains(r.asText())) {
                    kept.add(r);
                }
            });
            if (kept.isEmpty()) {
                s.remove("required");
            } else {
                s.set("required", kept);
            }
        }
        return s;
    }

    // ---------------------------------------------------------------- resources and jobs

    private static void resourceType(ObjectNode paths, ResourceTypeDefinition t) {
        String word = McpTools.toolWord(t.plural());
        ObjectNode list = operation("list_" + word, "List " + t.plural(),
                "The " + t.plural() + " in the resource registry" + (t.description() == null ? "" : ". " + t.description()), TAG_RESOURCES);
        list.put("x-resource-type", t.name());
        ArrayNode params = list.putArray("parameters");
        ObjectNode status = params.addObject();
        status.put("name", "status");
        status.put("in", "query");
        status.put("required", false);
        ObjectNode statusSchema = status.putObject("schema");
        statusSchema.put("type", "string");
        if (t.states() != null && !t.states().isEmpty()) {
            ArrayNode e = statusSchema.putArray("enum");
            t.states().forEach(e::add);
        }
        ObjectNode owner = params.addObject();
        owner.put("name", "owner");
        owner.put("in", "query");
        owner.put("required", false);
        owner.putObject("schema").put("type", "string");
        ObjectNode listResponses = list.putObject("responses");
        ObjectNode array = F.objectNode();
        array.put("type", "array");
        array.set("items", ref("Resource"));
        listResponses.set("200", response("Resources of the type", array));
        pathItem(paths, t.readPath()).set("get", list);

        ObjectNode get = operation("get_" + word, "Get " + t.name(), "One " + t.name() + " by id", TAG_RESOURCES);
        get.put("x-resource-type", t.name());
        ArrayNode getParams = get.putArray("parameters");
        ObjectNode id = getParams.addObject();
        id.put("name", "id");
        id.put("in", "path");
        id.put("required", true);
        id.putObject("schema").put("type", "string");
        ObjectNode getResponses = get.putObject("responses");
        getResponses.set("200", response("The resource", ref("Resource")));
        getResponses.set("404", response("Unknown id", ref("ErrorResponse")));
        pathItem(paths, t.readPath() + "/{id}").set("get", get);
    }

    private static void jobs(ObjectNode paths) {
        ObjectNode list = operation("list_jobs", "List jobs", "Jobs, newest first; every filter is optional", TAG_JOBS);
        ArrayNode params = list.putArray("parameters");
        for (String q : List.of("workflow", "status", "parent")) {
            ObjectNode p = params.addObject();
            p.put("name", q);
            p.put("in", "query");
            p.put("required", false);
            p.putObject("schema").put("type", "string");
        }
        ObjectNode limit = params.addObject();
        limit.put("name", "limit");
        limit.put("in", "query");
        limit.put("required", false);
        limit.putObject("schema").put("type", "integer");
        ObjectNode array = F.objectNode();
        array.put("type", "array");
        array.set("items", ref("JobSummary"));
        list.putObject("responses").set("200", response("Jobs", array));
        pathItem(paths, "/api/v1/jobs").set("get", list);

        ObjectNode get = operation("get_job", "Get job", "A job with its steps, approvals, preview and output", TAG_JOBS);
        ArrayNode getParams = get.putArray("parameters");
        ObjectNode id = getParams.addObject();
        id.put("name", "id");
        id.put("in", "path");
        id.put("required", true);
        id.putObject("schema").put("type", "string");
        ObjectNode getResponses = get.putObject("responses");
        getResponses.set("200", response("The job", ref("JobDetail")));
        getResponses.set("404", response("Unknown id", ref("ErrorResponse")));
        pathItem(paths, "/api/v1/jobs/{id}").set("get", get);

        ObjectNode events = operation("job_events", "Job events", "Server-sent events of a job: history first, then live until it ends", TAG_JOBS);
        ArrayNode eventParams = events.putArray("parameters");
        eventParams.add(id.deepCopy());
        ObjectNode ev = events.putObject("responses").putObject("200");
        ev.put("description", "A stream of JobEvent");
        ev.putObject("content").putObject("text/event-stream").set("schema", ref("JobEvent"));
        pathItem(paths, "/api/v1/jobs/{id}/events").set("get", events);
    }

    // ---------------------------------------------------------------- shared schemas

    private static ObjectNode sharedSchemas(ObjectNode perWorkflow) {
        ObjectNode s = F.objectNode();
        s.set("ErrorResponse", object(Map.of("error", str("What went wrong")), List.of("error")));
        s.set("Problem", object(Map.of(
                "level", enumeration("error", "warning"),
                "code", str("A stable problem code, e.g. schema"),
                "step", str(null),
                "field", str("The field at fault, as a JSON pointer"),
                "message", str(null)), List.of("level", "code", "message")));
        ObjectNode problems = array(ref("Problem"));
        s.set("ProblemResponse", object(Map.of("error", str(null), "problems", problems), List.of("error", "problems")));
        ObjectNode row = object(Map.of("index", integer("The row's index in items"), "problems", array(ref("Problem"))), List.of("index", "problems"));
        s.set("BatchProblemResponse", object(Map.of("error", str(null), "rows", array(row)), List.of("error", "rows")));
        s.set("JobAccepted", object(Map.of(
                "jobId", str("The job to poll at /api/v1/jobs/{id}"),
                "status", str("queued"),
                "links", object(Map.of("job", str("The job URL")), List.of("job"))), List.of("jobId", "status", "links")));
        s.set("BatchAccepted", object(Map.of(
                "jobId", str("The parent job of the batch"),
                "rows", integer("How many rows the batch holds"),
                "status", str(null),
                "links", object(Map.of("job", str("The job URL")), List.of())), List.of("jobId", "rows")));
        s.set("CommandAccepted", object(Map.of("id", str(null), "status", str(null), "links", freeObject()), List.of("id", "status")));
        s.set("RolloutPolicy", McpTools.rolloutSchema(null));
        s.set("PolicyRule", object(Map.of("id", str(null), "result", str(null), "message", str(null)), List.of("id", "result")));
        s.set("PolicyDecision", object(Map.of(
                "decision", enumeration("allow", "deny", "needs-approval"),
                "rules", array(ref("PolicyRule")),
                "engine", enumeration("mock", "opa")), List.of("decision", "rules", "engine")));
        s.set("PreviewStep", object(Map.of(
                "id", str(null), "name", str(null),
                "effect", enumeration("create", "change", "delete", "read", "none", "unknown"),
                "detail", str(null),
                "skipped", F.objectNode().put("type", "boolean").put("description", "The dry run on this input does not take the step")),
                List.of("id", "name", "effect")));
        s.set("AffectedResource", object(Map.of("id", str(null), "type", str(null), "name", str(null), "via", str("The step that touches it")), List.of("id", "type")));
        s.set("Cost", object(Map.of("monthly", number(null), "currency", str(null), "detail", str(null)), List.of("monthly", "currency")));
        s.set("Preview", object(Map.of(
                "workflow", str(null), "version", str(null),
                "steps", array(ref("PreviewStep")),
                "affectedResources", array(ref("AffectedResource")),
                "cost", ref("Cost"),
                "policy", ref("PolicyDecision")), List.of("workflow", "version", "steps", "affectedResources", "policy")));
        s.set("ResourceOrigin", object(Map.of("jobId", str(null), "workflow", str(null), "version", str(null)), List.of()));
        s.set("Resource", object(fields(
                "id", str(null), "type", str(null), "name", str(null), "status", str(null),
                "attributes", freeObject(), "owner", str(null), "visibility", str(null),
                "createdBy", ref("ResourceOrigin"), "builtFrom", array(str(null)),
                "createdAt", dateTime(), "updatedAt", dateTime()), List.of("id", "type", "status", "attributes")));
        s.set("JobSummary", object(fields(
                "id", str(null), "workflow", str(null), "title", str(null), "version", str(null),
                "status", enumeration("queued", "running", "waiting-approval", "needs-attention", "succeeded", "failed", "cancelled", "rolled-back"),
                "requester", str(null), "channel", enumeration("catalog", "rest", "mcp", "trigger", "batch"),
                "startedAt", dateTime(), "endedAt", dateTime(), "durationMs", integer(null), "parentId", str(null),
                "kind", enumeration("single", "batch"), "progress", freeObject()), List.of("id", "workflow", "status")));
        s.set("JobStep", object(fields(
                "id", str(null), "name", str(null), "type", str(null),
                "status", enumeration("pending", "running", "waiting", "succeeded", "failed", "skipped", "compensated"),
                "attempt", integer(null), "startedAt", dateTime(), "endedAt", dateTime(), "durationMs", integer(null),
                "input", freeObject(), "output", freeObject(), "error", freeObject()), List.of("id", "status")));
        ObjectNode jobDetail = F.objectNode();
        ArrayNode allOf = jobDetail.putArray("allOf");
        allOf.add(ref("JobSummary"));
        allOf.add(object(fields(
                "input", freeObject(), "output", freeObject(), "steps", array(ref("JobStep")),
                "approvals", array(freeObject()), "preview", ref("Preview"), "children", array(ref("JobSummary")),
                "batch", freeObject(), "attention", freeObject(), "traceId", str(null),
                "links", object(Map.of("temporal", str("The job's execution in the Temporal UI (platform.temporal.ui-url)")), List.of())), List.of()));
        s.set("JobDetail", jobDetail);
        s.set("JobEvent", object(Map.of(
                "seq", integer(null), "at", dateTime(), "jobId", str(null),
                "type", enumeration("job", "step", "approval", "attention", "batch", "log"), "data", freeObject()), List.of("seq", "at", "jobId", "type", "data")));
        perWorkflow.fields().forEachRemaining(e -> s.set(e.getKey(), e.getValue()));
        return s;
    }

    // ---------------------------------------------------------------- small builders

    private static ObjectNode operation(String operationId, String summary, String description, String tag) {
        ObjectNode op = F.objectNode();
        op.put("operationId", operationId);
        op.put("summary", summary);
        if (description != null && !description.isBlank()) {
            op.put("description", description);
        }
        op.putArray("tags").add(tag);
        return op;
    }

    private static ObjectNode pathItem(ObjectNode paths, String path) {
        JsonNode existing = paths.get(path);
        if (existing != null && existing.isObject()) {
            return (ObjectNode) existing;
        }
        return paths.putObject(path);
    }

    private static ObjectNode requestBody(JsonNode schema, boolean required) {
        ObjectNode b = F.objectNode();
        b.put("required", required);
        b.putObject("content").putObject(JSON).set("schema", schema);
        return b;
    }

    private static ObjectNode response(String description, JsonNode schema) {
        ObjectNode r = F.objectNode();
        r.put("description", description);
        r.putObject("content").putObject(JSON).set("schema", schema);
        return r;
    }

    private static ObjectNode ref(String name) {
        ObjectNode r = F.objectNode();
        r.put("$ref", SCHEMAS + name);
        return r;
    }

    private static ObjectNode object(Map<String, JsonNode> properties, List<String> required) {
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        ObjectNode props = o.putObject("properties");
        properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> props.set(e.getKey(), e.getValue()));
        if (required != null && !required.isEmpty()) {
            ArrayNode r = o.putArray("required");
            required.forEach(r::add);
        }
        return o;
    }

    /** Alternating name, schema pairs, for the shapes with more fields than {@code Map.of} takes. */
    private static Map<String, JsonNode> fields(Object... kv) {
        Map<String, JsonNode> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], (JsonNode) kv[i + 1]);
        }
        return m;
    }

    private static ObjectNode freeObject() {
        ObjectNode o = F.objectNode();
        o.put("type", "object");
        o.put("additionalProperties", true);
        return o;
    }

    private static ObjectNode array(JsonNode items) {
        ObjectNode a = F.objectNode();
        a.put("type", "array");
        a.set("items", items);
        return a;
    }

    private static ObjectNode str(String description) {
        ObjectNode s = F.objectNode();
        s.put("type", "string");
        if (description != null) {
            s.put("description", description);
        }
        return s;
    }

    private static ObjectNode integer(String description) {
        ObjectNode s = F.objectNode();
        s.put("type", "integer");
        if (description != null) {
            s.put("description", description);
        }
        return s;
    }

    private static ObjectNode number(String description) {
        ObjectNode s = F.objectNode();
        s.put("type", "number");
        if (description != null) {
            s.put("description", description);
        }
        return s;
    }

    private static ObjectNode dateTime() {
        ObjectNode s = F.objectNode();
        s.put("type", "string");
        s.put("format", "date-time");
        return s;
    }

    private static ObjectNode enumeration(String... values) {
        ObjectNode s = F.objectNode();
        s.put("type", "string");
        ArrayNode e = s.putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        return s;
    }
}
