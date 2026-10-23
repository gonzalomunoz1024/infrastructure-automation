package com.iaf.console.fake.runs.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.method;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.common.Sse;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.CatalogEntries;
import com.iaf.console.fake.content.domain.WorkflowDef;
import com.iaf.console.fake.events.application.EventFeed;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.application.JobFilter;
import com.iaf.console.fake.runs.application.Jobs;
import com.iaf.console.fake.runs.domain.ApprovalRecord;
import com.iaf.console.fake.runs.domain.CommandResult;
import com.iaf.console.fake.runs.domain.Job;

import reactor.core.publisher.Mono;

/**
 * The job routes: the generic start, batches, the lists and streams, the commands that steer a
 * job, the approvals, and the routes generated from every workflow's {@code api} block (start,
 * preview and batch).
 */
public final class JobRoutes {

    public static final String WORKFLOW_VERSION = "X-Workflow-Version";
    private static final String LABEL_PREFIX = "label.";
    private static final List<String> JOB_EVENT_TYPES = List.of("job.", "batch.");
    private static final ParameterizedTypeReference<ServerSentEvent<ObjectNode>> JOB_EVENTS = new ParameterizedTypeReference<>() {
    };

    private final Jobs jobs;
    private final ContentStore content;
    private final Commands commands;
    private final EventFeed events;

    public JobRoutes(Jobs jobs, ContentStore content, Commands commands, EventFeed events) {
        this.jobs = jobs;
        this.content = content;
        this.commands = commands;
        this.events = events;
    }

    public RouterFunction<ServerResponse> routes() {
        RouterFunction<ServerResponse> routes = route(GET("/api/v1/jobs"), this::list)
                .andRoute(POST("/api/v1/jobs"), this::start)
                .andRoute(POST("/api/v1/jobs:batch"), this::startBatch)
                .andRoute(GET("/api/v1/jobs/events/stream"), this::stream)
                .andRoute(GET("/api/v1/jobs/{id}"), this::get)
                .andRoute(GET("/api/v1/jobs/{id}/events"), this::jobEvents)
                .andRoute(POST("/api/v1/jobs/{id}:cancel"), request -> command(request, "cancel"))
                .andRoute(POST("/api/v1/jobs/{id}:retry"), request -> command(request, "retry"))
                .andRoute(POST("/api/v1/jobs/{id}:skip"), request -> command(request, "skip"))
                .andRoute(POST("/api/v1/jobs/{id}:abort"), request -> command(request, "abort"))
                .andRoute(POST("/api/v1/jobs/{id}:rerun"), request -> command(request, "rerun"))
                .andRoute(POST("/api/v1/jobs/{id}:retry-failed-rows"), request -> command(request, "retry-failed-rows"))
                .andRoute(GET("/api/v1/approvals"), this::approvals)
                .andRoute(GET("/api/v1/approvals/{id}"), this::approval)
                .andRoute(POST("/api/v1/approvals/{id}:approve"), request -> decide(request, true))
                .andRoute(POST("/api/v1/approvals/{id}:reject"), request -> decide(request, false));
        for (WorkflowDef workflow : content.workflows()) {
            if (workflow.path() == null) {
                continue;
            }
            String previewPath = CatalogEntries.previewPath(workflow);
            if (previewPath != null) {
                routes = routes.andRoute(POST(pattern(previewPath)), request -> preview(request, workflow));
            }
            String batchPath = CatalogEntries.batchPath(workflow);
            if (batchPath != null) {
                routes = routes.andRoute(POST(pattern(batchPath)), request -> generatedBatch(request, workflow));
            }
            routes = routes.andRoute(method(HttpMethod.valueOf(workflow.method())).and(path(pattern(workflow.path()))),
                    request -> generatedStart(request, workflow));
        }
        return routes;
    }

    /** A route pattern whose variables stop at a colon, so {@code /vms/{vmId}:preview} never takes {@code /vms/x:restart:preview}. */
    static String pattern(String path) {
        return path.replaceAll("\\{([A-Za-z0-9_]+)\\}", "{$1:[^:]+}");
    }

    // jobs

    private Mono<ServerResponse> list(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        Map<String, String> labels = new TreeMap<>();
        request.queryParams().forEach((name, values) -> {
            if (name.startsWith(LABEL_PREFIX) && !values.isEmpty()) {
                String label = name.substring(LABEL_PREFIX.length());
                if (!label.matches("[a-z0-9-]{1,32}")) {
                    throw ApiException.badRequest("label name '" + label + "' must match [a-z0-9-]{1,32}");
                }
                labels.put(label, values.get(0));
            }
        });
        JobFilter filter = new JobFilter(Http.query(request, "workflow").orElse(null), Http.query(request, "status").orElse(null),
                Http.query(request, "parent").orElse(null), Http.intQuery(request, "limit").orElse(null), Http.query(request, "account").orElse(null),
                Http.query(request, "region").orElse(null), Http.query(request, "wfrn").orElse(null), Http.query(request, "consoleRequestId").orElse(null),
                labels, Http.longQuery(request, "updatedAfter").orElse(null), Http.query(request, "after").orElse(null));
        Jobs.Listing listing = jobs.list(filter, caller);
        List<ObjectNode> body = new ArrayList<>();
        for (Job job : listing.items()) {
            body.add(job.summary());
        }
        ServerResponse.BodyBuilder builder = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
        if (listing.next() != null) {
            builder = builder.header(HttpHeaders.LINK, Http.nextLink(request, listing.next()));
        }
        return builder.bodyValue(body);
    }

    private Mono<ServerResponse> start(ServerRequest request) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            Jobs.StartRequest start = Jobs.StartRequest.of(body);
            String pinned = Http.header(request, WORKFLOW_VERSION);
            if (start.version() == null && pinned != null && !pinned.isBlank()) {
                start = new Jobs.StartRequest(start.workflow(), pinned.trim(), start.input(), start.channel(), start.traceId(), start.changeId());
            }
            Job job = jobs.start(caller, Callers.context(request), start, "rest");
            return CommandResult.accepted(commandAccepted(job.id(), job.status().id(), links(job)));
        });
    }

    private Mono<ServerResponse> startBatch(ServerRequest request) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            Job parent = jobs.startBatch(caller, Callers.context(request), body, "batch");
            ObjectNode links = links(parent);
            links.put("rows", parent.batch().total());
            return CommandResult.accepted(commandAccepted(parent.id(), parent.status().id(), links));
        });
    }

    private Mono<ServerResponse> stream(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        return Sse.envelopes(events.stream(Http.resumeFrom(request, events.lastSeq()), JOB_EVENT_TYPES, caller));
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        Job job = visible(request, caller);
        return Http.ok(jobs.detail(job, caller));
    }

    private Mono<ServerResponse> jobEvents(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        Job job = visible(request, caller);
        return Sse.write(jobs.streamEvents(job)
                .map(record -> ServerSentEvent.builder(record.document()).id(String.valueOf(record.seq())).event(record.type()).build()), JOB_EVENTS);
    }

    private Mono<ServerResponse> command(ServerRequest request, String action) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            Job job = visible(request, caller);
            if (!jobs.steers(caller, job)) {
                throw ApiException.forbidden("job.notAllowed: " + caller.requester() + " may not steer job " + job.id());
            }
            Job result = switch (action) {
                case "cancel" -> jobs.cancel(job, caller);
                case "retry" -> jobs.retry(job, body, caller, Callers.context(request));
                case "skip" -> jobs.skip(job, body, caller);
                case "abort" -> jobs.abort(job, caller);
                case "rerun" -> jobs.rerun(job, body, caller, Callers.context(request));
                case "retry-failed-rows" -> jobs.retryFailedRows(job);
                default -> throw ApiException.notFound("command", action);
            };
            return CommandResult.accepted(commandAccepted(result.id(), result.status().id(), links(result)));
        });
    }

    private Job visible(ServerRequest request, Caller caller) {
        String id = request.pathVariable("id");
        return jobs.visible(id, caller).orElseThrow(() -> ApiException.notFound("job", id));
    }

    // approvals

    private Mono<ServerResponse> approvals(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        String status = Http.query(request, "status").orElse(null);
        if (status != null && !List.of("pending", "approved", "rejected", "expired").contains(status)) {
            throw ApiException.badRequest("status must be pending, approved, rejected or expired");
        }
        List<ObjectNode> body = new ArrayList<>();
        for (ApprovalRecord approval : jobs.approvals(status, caller)) {
            body.add(approval.document());
        }
        return Http.ok(body);
    }

    private Mono<ServerResponse> approval(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        String id = request.pathVariable("id");
        ApprovalRecord approval = jobs.visibleApproval(id, caller).orElseThrow(() -> ApiException.notFound("approval", id));
        return Http.ok(approval.document());
    }

    private Mono<ServerResponse> decide(ServerRequest request, boolean approve) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            String id = request.pathVariable("id");
            ApprovalRecord approval = jobs.visibleApproval(id, caller).orElseThrow(() -> ApiException.notFound("approval", id));
            Job job = jobs.decideApproval(approval, approve, caller, body.path("comment").asText(null));
            ObjectNode links = links(job);
            links.put("approval", "/api/v1/approvals/" + approval.id());
            return CommandResult.accepted(commandAccepted(approval.id(), approve ? "approved" : "rejected", links));
        });
    }

    // generated routes

    private Mono<ServerResponse> generatedStart(ServerRequest request, WorkflowDef workflow) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            ObjectNode input = inputOf(request, body);
            String version = Http.header(request, WORKFLOW_VERSION);
            Jobs.StartRequest start = new Jobs.StartRequest(workflow.name(), version == null || version.isBlank() ? null : version.trim(), input, null,
                    null, null);
            Job job = jobs.start(caller, Callers.context(request), start, "rest");
            ObjectNode out = Json.object();
            out.put("jobId", job.id());
            out.put("status", job.status().id());
            out.set("links", links(job));
            return CommandResult.accepted(out);
        });
    }

    private Mono<ServerResponse> preview(ServerRequest request, WorkflowDef workflow) {
        return request.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> Mono.fromCallable(() -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            ObjectNode input = inputOf(request, Commands.parse(text));
            return jobs.preview(caller, Callers.context(request), workflow, input);
        })).flatMap(Http::ok);
    }

    private Mono<ServerResponse> generatedBatch(ServerRequest request, WorkflowDef workflow) {
        return commands.handle(request, body -> {
            Caller caller = Callers.of(request);
            Access.requireScope(caller, Scopes.JOBS_START);
            ObjectNode start = Json.object();
            start.put("workflow", workflow.name());
            String version = Http.header(request, WORKFLOW_VERSION);
            if (version != null && !version.isBlank()) {
                start.put("version", version.trim());
            }
            for (String field : new String[] {"items", "defaults", "rollout", "contexts", "traceId"}) {
                if (body.has(field)) {
                    start.set(field, body.get(field));
                }
            }
            start.put("channel", "batch");
            Job parent = jobs.startBatch(caller, Callers.context(request), start, "batch");
            ObjectNode out = Json.object();
            out.put("jobId", parent.id());
            out.put("rows", parent.batch().total());
            out.put("status", parent.status().id());
            out.set("links", links(parent));
            return CommandResult.accepted(out);
        });
    }

    /** The input of a generated route: the body with the path variables merged in. */
    private static ObjectNode inputOf(ServerRequest request, JsonNode body) {
        if (!body.isObject() && !body.isMissingNode() && !body.isNull()) {
            throw ApiException.badRequest("the body must be a JSON object");
        }
        ObjectNode input = body.isObject() ? ((ObjectNode) body).deepCopy() : Json.object();
        request.pathVariables().forEach(input::put);
        return input;
    }

    private static ObjectNode links(Job job) {
        ObjectNode links = Json.object();
        links.put("job", "/api/v1/jobs/" + job.id());
        links.put("self", "/api/v1/jobs/" + job.id());
        links.put("events", "/api/v1/jobs/" + job.id() + "/events");
        return links;
    }

    private static ObjectNode commandAccepted(String id, String status, ObjectNode links) {
        ObjectNode out = Json.object();
        out.put("id", id);
        out.put("status", status);
        out.set("links", links);
        return out;
    }
}
