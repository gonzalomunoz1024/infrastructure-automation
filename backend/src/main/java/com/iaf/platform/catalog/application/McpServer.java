package com.iaf.platform.catalog.application;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.domain.BatchProblemsException;
import com.iaf.platform.catalog.domain.InputProblemsException;
import com.iaf.platform.catalog.domain.JsonRpc;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.catalog.domain.McpTools.Tool;
import com.iaf.platform.catalog.domain.PlatformInfo;
import com.iaf.platform.catalog.domain.PolicyDeniedException;
import com.iaf.platform.catalog.ports.inbound.CatalogQueries;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.Ids;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.Problem;
import com.iaf.platform.common.schema.Schemas;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.identity.domain.Scopes;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The MCP server: JSON-RPC 2.0 over one HTTP POST per message (Streamable HTTP, no stream). A
 * tool call takes the same path as the REST gateway (validate, RBAC, policy, preview, start),
 * so an agent can do exactly what a person can, and nothing more. Identity comes from the
 * identity filter: a service account token or the dev header.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpServer {

    public static final String PROTOCOL_VERSION = "2025-06-18";
    public static final String CHANNEL = "mcp";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final CatalogQueries catalog;
    private final WorkflowInvoker invoker;
    private final ResourceQueries resources;
    private final RunsQueries runsQueries;
    private final RunsCommands runs;
    private final AssistantCommands assistant;
    private final ObjectMapper json;

    /** The HTTP answer: 200 with a body, or 202 without one when only notifications came in. */
    public record HttpReply(int status, JsonNode body) {
        public static final HttpReply ACCEPTED = new HttpReply(202, null);
    }

    /** One POST body: a request, a notification, or a batch (array) of them. */
    public Mono<HttpReply> post(String body, Identity who) {
        JsonNode parsed;
        try {
            parsed = body == null || body.isBlank() ? null : json.readTree(body);
        } catch (JsonProcessingException e) {
            return Mono.just(new HttpReply(200, JsonRpc.error(null, JsonRpc.PARSE_ERROR, "Parse error: " + e.getOriginalMessage(), null)));
        }
        if (parsed == null || parsed.isNull()) {
            return Mono.just(new HttpReply(200, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Empty request", null)));
        }
        if (parsed.isArray()) {
            if (parsed.isEmpty()) {
                return Mono.just(new HttpReply(200, JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Empty batch", null)));
            }
            return Flux.fromIterable(parsed)
                    .concatMap(r -> handle(r, who))
                    .collectList()
                    .map(responses -> {
                        if (responses.isEmpty()) {
                            return HttpReply.ACCEPTED;
                        }
                        ArrayNode arr = F.arrayNode();
                        responses.forEach(arr::add);
                        return new HttpReply(200, arr);
                    });
        }
        return handle(parsed, who).map(r -> new HttpReply(200, r)).defaultIfEmpty(HttpReply.ACCEPTED);
    }

    /** One message: a response for a request, nothing for a notification. */
    public Mono<JsonNode> handle(JsonNode request, Identity who) {
        var invalid = JsonRpc.invalidReason(request);
        if (invalid.isPresent()) {
            return Mono.just(JsonRpc.error(JsonRpc.id(request), JsonRpc.INVALID_REQUEST, invalid.get(), null));
        }
        if (JsonRpc.isNotification(request)) {
            log.debug("mcp notification {} from {}", request.get("method").asText(), who.id());
            return Mono.empty();
        }
        JsonNode id = JsonRpc.id(request);
        String method = request.get("method").asText();
        ObjectNode params = JsonRpc.params(request);
        Mono<JsonNode> result = switch (method) {
            case "initialize" -> Mono.just(initialize());
            case "ping" -> Mono.just(F.objectNode());
            case "tools/list" -> ServiceScopes.require(Scopes.REGISTRY_READ, "list the tools").then(catalog.tools()).map(tools -> {
                ObjectNode r = F.objectNode();
                r.set("tools", McpTools.toJson(tools));
                return r;
            });
            case "tools/call" -> call(params, who);
            default -> Mono.error(new MethodNotFound(method));
        };
        return result.map(r -> (JsonNode) JsonRpc.result(id, r)).onErrorResume(e -> Mono.just(error(id, e)));
    }

    private ObjectNode initialize() {
        ObjectNode r = F.objectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools").put("listChanged", true);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", PlatformInfo.NAME);
        info.put("title", PlatformInfo.TITLE);
        info.put("version", PlatformInfo.VERSION);
        r.put("instructions", "Tools are generated from the published workflows: <noun>_<verb> starts one job and returns a job id, "
                + "<noun>_<verb>_batch starts many in waves, list_<plural> and get_<plural> read the resource registry, "
                + "get_job_status follows a job, cancel_job stops one, draft_workflow asks the builder for a new workflow.");
        return r;
    }

    // ---------------------------------------------------------------- tools/call

    private Mono<JsonNode> call(ObjectNode params, Identity who) {
        String name = params.path("name").asText(null);
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("params.name is required"));
        }
        JsonNode arguments = params.get("arguments");
        ObjectNode args = arguments != null && arguments.isObject() ? (ObjectNode) arguments : F.objectNode();
        if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
            return Mono.error(new IllegalArgumentException("params.arguments must be an object"));
        }
        return catalog.tools().flatMap(tools -> {
            Tool tool = McpTools.find(tools, name).orElseThrow(() -> new IllegalArgumentException("Unknown tool '" + name + "'"));
            return ServiceScopes.require(scopeOf(tool), "call " + tool.name()).then(Mono.defer(() -> callTool(tool, args, who)));
        });
    }

    /** What a service account needs for a tool: starting or steering jobs, following them, reading the registry, drafting. */
    public static String scopeOf(Tool tool) {
        return switch (tool.kind()) {
            case INVOKE, BATCH, CANCEL_JOB -> Scopes.JOBS_START;
            case JOB_STATUS -> Scopes.JOBS_READ;
            case LIST_RESOURCES, GET_RESOURCE -> Scopes.REGISTRY_READ;
            case DRAFT_WORKFLOW -> Scopes.WORKFLOWS_WRITE;
        };
    }

    private Mono<ObjectNode> callTool(Tool tool, ObjectNode args, Identity who) {
        return switch (tool.kind()) {
            case INVOKE -> invoker.invoke(tool.workflow(), args, who, CHANNEL, Ids.next("trace")).map(this::ok);
            case BATCH -> invoker.batch(tool.workflow(), args, Map.of(), who, CHANNEL).map(this::ok);
            case LIST_RESOURCES -> {
                check(tool, args);
                yield resources.list(tool.type().name(), text(args, "owner"), text(args, "status")).collectList().map(list -> {
                    ObjectNode r = F.objectNode();
                    r.put("type", tool.type().name());
                    r.put("count", list.size());
                    r.set("items", json.valueToTree(list));
                    return ok(r);
                });
            }
            case GET_RESOURCE -> {
                check(tool, args);
                String rid = text(args, "id");
                yield resources.get(rid)
                        .filter(r -> r.type().equals(tool.type().name()))
                        .map(this::ok)
                        .defaultIfEmpty(toolError("No " + tool.type().name() + " with id " + rid));
            }
            case JOB_STATUS -> {
                check(tool, args);
                String jobId = text(args, "jobId");
                yield runsQueries.job(jobId).map(this::ok).defaultIfEmpty(toolError("No job with id " + jobId));
            }
            case CANCEL_JOB -> {
                check(tool, args);
                yield runs.cancel(text(args, "jobId")).map(this::ok);
            }
            case DRAFT_WORKFLOW -> {
                check(tool, args);
                yield assistant.draftWorkflow(text(args, "description")).map(this::ok);
            }
        };
    }

    private static void check(Tool tool, JsonNode args) {
        List<Problem> problems = Schemas.validate(tool.inputSchema(), args);
        if (!problems.isEmpty()) {
            throw new InputProblemsException("The arguments of " + tool.name() + " have " + problems.size() + " problem" + (problems.size() == 1 ? "" : "s"), problems);
        }
    }

    private static String text(JsonNode args, String field) {
        JsonNode v = args.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** A tool result: the JSON as text content and as structured content. */
    private ObjectNode ok(Object value) {
        JsonNode node = value instanceof JsonNode n ? n : json.valueToTree(value);
        ObjectNode r = F.objectNode();
        ObjectNode content = r.putArray("content").addObject();
        content.put("type", "text");
        try {
            content.put("text", json.writeValueAsString(node));
        } catch (JsonProcessingException e) {
            content.put("text", node.toString());
        }
        r.set("structuredContent", node);
        return r;
    }

    private static ObjectNode toolError(String message) {
        ObjectNode r = F.objectNode();
        ObjectNode content = r.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", message);
        r.put("isError", true);
        return r;
    }

    // ---------------------------------------------------------------- errors

    private static final class MethodNotFound extends RuntimeException {
        MethodNotFound(String method) {
            super("Method not found: " + method);
        }
    }

    private JsonNode error(JsonNode id, Throwable e) {
        if (e instanceof MethodNotFound) {
            return JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, e.getMessage(), null);
        }
        if (e instanceof InputProblemsException ip) {
            ObjectNode data = F.objectNode();
            data.set("problems", json.valueToTree(ip.problems()));
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage(), data);
        }
        if (e instanceof BatchProblemsException bp) {
            ObjectNode data = F.objectNode();
            data.set("rows", json.valueToTree(bp.rows()));
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage(), data);
        }
        if (e instanceof IllegalArgumentException) {
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage(), null);
        }
        if (e instanceof PolicyDeniedException pd) {
            ObjectNode data = F.objectNode();
            data.put("forbidden", true);
            data.set("rules", json.valueToTree(pd.rules()));
            if (pd.engine() != null) {
                data.put("engine", pd.engine());
            }
            return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, e.getMessage(), data);
        }
        if (e instanceof ForbiddenException) {
            ObjectNode data = F.objectNode();
            data.put("forbidden", true);
            return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, e.getMessage(), data);
        }
        if (e instanceof NotFoundException) {
            return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, e.getMessage(), null);
        }
        log.error("mcp call failed", e);
        return JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, e.getMessage() == null ? "internal error" : e.getMessage(), null);
    }
}
