package com.iaf.platform.catalog.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.catalog.domain.ApiDeprecations;
import com.iaf.platform.catalog.domain.BatchProblemsException;
import com.iaf.platform.catalog.domain.InputProblemsException;
import com.iaf.platform.catalog.domain.Inputs;
import com.iaf.platform.catalog.domain.PolicyDeniedException;
import com.iaf.platform.catalog.domain.ReservedNouns;
import com.iaf.platform.catalog.domain.RouteTable;
import com.iaf.platform.catalog.domain.RouteTable.RouteMatch;
import com.iaf.platform.catalog.domain.VersionPin;
import com.iaf.platform.catalog.ports.inbound.CatalogQueries;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.common.domain.TooManyRequestsException;
import com.iaf.platform.common.tracing.TraceIds;
import com.iaf.platform.common.tracing.Traces;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.identity.domain.Scopes;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.InvalidInputException;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import io.micrometer.observation.Observation;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * The generated REST gateway. Runs right after the identity filter and matches the request's
 * method and path against the live route table: the published workflows' operations (invoke,
 * {@code :batch}, {@code :preview}) and the resource reads of every type. Platform routes win:
 * a path whose first segment is a reserved noun is never matched, and everything that matches
 * nothing continues down the chain to the controllers (404). {@code X-Workflow-Version} (or
 * {@code ?version=}) pins a workflow operation to one version. Every matched request is a
 * {@code iaf.gateway} span under the request's own (which continues an incoming
 * {@code traceparent}); the job it starts carries that trace id.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class GeneratedRouteWebFilter implements WebFilter {

    public static final String CHANNEL = "rest";
    public static final String TRACEPARENT = TraceIds.TRACEPARENT;
    public static final String SPAN = "iaf.gateway";
    /** The exchange attribute holding the trace id of the gateway span. */
    static final String TRACE_ID_ATTRIBUTE = "platform.traceId";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final CatalogQueries catalog;
    private final WorkflowInvoker invoker;
    private final ResourceQueries resources;
    private final ObjectMapper json;
    private final Traces traces;

    public GeneratedRouteWebFilter(CatalogQueries catalog, WorkflowInvoker invoker, ResourceQueries resources, ObjectMapper json) {
        this(catalog, invoker, resources, json, Traces.off());
    }

    @Autowired
    public GeneratedRouteWebFilter(CatalogQueries catalog, WorkflowInvoker invoker, ResourceQueries resources, ObjectMapper json, Traces traces) {
        this.catalog = catalog;
        this.invoker = invoker;
        this.resources = resources;
        this.json = json;
        this.traces = traces;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value();
        String method = exchange.getRequest().getMethod().name();
        if (!ReservedNouns.isGenerated(path) || HttpMethod.OPTIONS.matches(method)) {
            return chain.filter(exchange);   // platform routes, and CORS preflights, belong to the framework
        }
        return catalog.routes().flatMap(table -> {
            var match = table.match(method, path);
            if (match.isPresent()) {
                return traced(exchange, decoded(match.get()));
            }
            var allowed = table.allowedMethods(path);
            if (!allowed.isEmpty()) {
                exchange.getResponse().getHeaders().set("Allow", String.join(", ", allowed));
                return write(exchange, HttpStatus.METHOD_NOT_ALLOWED, body(method + " is not allowed on " + path + " (allowed: " + String.join(", ", allowed) + ")"));
            }
            return chain.filter(exchange);
        });
    }

    /**
     * One matched request as an {@code iaf.gateway} span: under the request's observation (which
     * continues an incoming {@code traceparent}), tagged with the operation and its workflow or
     * resource type; its trace id is the one the started job carries, and it stays the current
     * observation downstream so the job's own span hangs under it. Only a 5xx marks it failed.
     */
    private Mono<Void> traced(ServerWebExchange exchange, RouteMatch match) {
        return Mono.deferContextual(ctx -> {
            RouteTable.Route route = match.route();
            String kind = route.kind().name().toLowerCase();
            String target = route.isWorkflow() ? route.workflow().name() : route.type().name();
            Observation span = traces.start(SPAN, "gateway " + kind + " " + target, Traces.current(ctx),
                    Map.of("operation", kind, "target", target), Map.of("http.route", route.path()));
            String traceId = traces.traceId(span);
            if (traceId == null) {
                traceId = TraceIds.parse(exchange.getRequest().getHeaders().getFirst(TRACEPARENT)).map(TraceIds.Parent::traceId)
                        .orElseGet(TraceIds::newTraceId);
            }
            String id = traceId;
            exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, id);
            traces.remember(id, span);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            return handle(exchange, match)
                    .doOnError(failure::set)
                    .onErrorResume(e -> error(exchange, e))
                    .contextWrite(c -> Traces.with(c, span))
                    .doFinally(signal -> {
                        traces.forget(id, span);
                        int status = statusOf(exchange);
                        span.lowCardinalityKeyValue("status", String.valueOf(status));
                        Traces.stop(span, status >= 500 ? failure.get() : null);
                    });
        });
    }

    private static int statusOf(ServerWebExchange exchange) {
        HttpStatusCode status = exchange.getResponse().getStatusCode();
        return status == null ? 200 : status.value();
    }

    private Mono<Void> handle(ServerWebExchange exchange, RouteMatch match) {
        RouteTable.Route route = match.route();
        Map<String, String> params = match.params();
        // a service account starts jobs with jobs:start and reads the resource registry with registry:read
        boolean read = route.kind() == RouteTable.Kind.LIST_RESOURCES || route.kind() == RouteTable.Kind.GET_RESOURCE;
        try {
            Scopes.check(exchange.getAttribute(ServiceScopes.ATTRIBUTE), read ? Scopes.REGISTRY_READ : Scopes.JOBS_START,
                    read ? "read " + route.type().name() + " resources" : "start " + route.workflow().name());
        } catch (ForbiddenException e) {
            return Mono.error(e);
        }
        deprecationHeaders(exchange, route, params);
        return switch (route.kind()) {
            case INVOKE -> identity(exchange).flatMap(who -> input(exchange, route.workflow().definition(), params)
                    .flatMap(in -> invoker.invoke(route.workflow(), in, who, CHANNEL, traceId(exchange), pinnedVersion(exchange, route)))
                    .flatMap(started -> write(exchange, HttpStatus.ACCEPTED, started)));
            case BATCH -> identity(exchange).flatMap(who -> body(exchange)
                    .flatMap(b -> invoker.batch(route.workflow(), b, params, who, CHANNEL, pinnedVersion(exchange, route), traceId(exchange)))
                    .flatMap(started -> write(exchange, HttpStatus.ACCEPTED, started)));
            case PREVIEW -> identity(exchange).flatMap(who -> input(exchange, route.workflow().definition(), params)
                    .flatMap(in -> invoker.preview(route.workflow(), in, who, pinnedVersion(exchange, route)))
                    .flatMap(preview -> write(exchange, HttpStatus.OK, preview)));
            case LIST_RESOURCES -> {
                var query = exchange.getRequest().getQueryParams();
                yield resources.list(route.type().name(), query.getFirst("owner"), query.getFirst("status"))
                        .collectList()
                        .flatMap(list -> write(exchange, HttpStatus.OK, list));
            }
            case GET_RESOURCE -> {
                String id = params.get("id");
                yield resources.get(id)
                        .filter(r -> route.type().name().equals(r.type()))
                        .switchIfEmpty(Mono.error(new NotFoundException(route.type().name(), id)))
                        .flatMap(r -> write(exchange, HttpStatus.OK, r));
            }
        };
    }

    /** A deprecated workflow's operations answer with {@code Deprecation}, {@code Sunset} and {@code Link} on every response. */
    private static void deprecationHeaders(ServerWebExchange exchange, RouteTable.Route route, Map<String, String> params) {
        if (route.isWorkflow() && route.workflow().definition() != null) {
            ApiDeprecations.headers(route.workflow().definition().deprecation(), params).forEach(exchange.getResponse().getHeaders()::set);
        }
    }

    // ---------------------------------------------------------------- request pieces

    /** Path parameters arrive percent-encoded; the job input wants the real value. */
    private static RouteMatch decoded(RouteMatch match) {
        Map<String, String> params = new LinkedHashMap<>();
        match.params().forEach((k, v) -> params.put(k, UriUtils.decode(v, StandardCharsets.UTF_8)));
        return new RouteMatch(match.route(), params);
    }

    private static Mono<Identity> identity(ServerWebExchange exchange) {
        Identity who = exchange.getAttribute(IdentityWebFilter.ATTRIBUTE);
        return who != null ? Mono.just(who) : CurrentIdentity.current();
    }

    /**
     * The version this call pins ({@code X-Workflow-Version}, or {@code ?version=} where that is
     * not the workflow's own input); null when it pins none. 400 when the two disagree.
     */
    static String pinnedVersion(ServerWebExchange exchange, RouteTable.Route route) {
        String header = exchange.getRequest().getHeaders().getFirst(VersionPin.HEADER);
        String query = VersionPin.queryPins(exchange.getRequest().getMethod().name(), route.workflow().definition().inputSchema())
                ? exchange.getRequest().getQueryParams().getFirst(VersionPin.PARAM) : null;
        return VersionPin.of(header, query);
    }

    /** A read takes its input from the query string, anything else from the JSON body; the path parameters go on top. */
    private Mono<ObjectNode> input(ServerWebExchange exchange, WorkflowDefinition d, Map<String, String> params) {
        JsonNode schema = d.inputSchema();
        if ("GET".equals(exchange.getRequest().getMethod().name())) {
            Map<String, String> query = new LinkedHashMap<>();
            exchange.getRequest().getQueryParams().forEach((k, v) -> query.put(k, v.isEmpty() ? null : v.get(0)));
            if (VersionPin.queryPins("GET", schema)) {
                query.remove(VersionPin.PARAM);   // the pin, not input
            }
            return Mono.fromSupplier(() -> Inputs.of(Inputs.ofQuery(query, schema), params, schema));
        }
        return body(exchange).map(b -> Inputs.of(b, params, schema));
    }

    private Mono<JsonNode> body(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .defaultIfEmpty(new byte[0])
                .map(bytes -> {
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (text.isBlank()) {
                        return (JsonNode) F.objectNode();
                    }
                    try {
                        JsonNode node = json.readTree(text);
                        return node == null || node.isNull() ? F.objectNode() : node;
                    } catch (JsonProcessingException e) {
                        throw new IllegalArgumentException("Malformed JSON body: " + e.getOriginalMessage());
                    }
                });
    }

    /** The trace id of this request's gateway span; outside one, the W3C trace id from {@code traceparent}, else a fresh one. */
    static String traceId(ServerWebExchange exchange) {
        String traced = exchange.getAttribute(TRACE_ID_ATTRIBUTE);
        if (traced != null) {
            return traced;
        }
        return TraceIds.parse(exchange.getRequest().getHeaders().getFirst(TRACEPARENT)).map(TraceIds.Parent::traceId)
                .orElseGet(TraceIds::newTraceId);
    }

    // ---------------------------------------------------------------- responses

    private Mono<Void> error(ServerWebExchange exchange, Throwable e) {
        if (e instanceof InputProblemsException ip) {
            Map<String, Object> b = body(e.getMessage());
            b.put("problems", ip.problems());
            return write(exchange, HttpStatus.BAD_REQUEST, b);
        }
        if (e instanceof BatchProblemsException bp) {
            Map<String, Object> b = body(e.getMessage());
            b.put("rows", bp.rows());
            return write(exchange, HttpStatus.BAD_REQUEST, b);
        }
        if (e instanceof InvalidInputException ii) {
            Map<String, Object> b = body(e.getMessage());
            if (ii.problems() != null) {
                b.put("problems", ii.problems());
            }
            if (ii.rows() != null) {
                b.put("rows", ii.rows());
            }
            return write(exchange, HttpStatus.BAD_REQUEST, b);
        }
        if (e instanceof IllegalArgumentException) {
            return write(exchange, HttpStatus.BAD_REQUEST, body(e.getMessage()));
        }
        if (e instanceof PolicyDeniedException pd) {
            Map<String, Object> b = body(e.getMessage());
            b.put("rules", pd.rules());
            if (pd.engine() != null) {
                b.put("engine", pd.engine());
            }
            return write(exchange, HttpStatus.FORBIDDEN, b);
        }
        if (e instanceof ForbiddenException) {
            return write(exchange, HttpStatus.FORBIDDEN, body(e.getMessage()));
        }
        if (e instanceof NotFoundException) {
            return write(exchange, HttpStatus.NOT_FOUND, body(e.getMessage()));
        }
        if (e instanceof ConflictException c) {
            Map<String, Object> b = body(e.getMessage());
            if (c.detail() != null) {
                b.put("detail", c.detail());
            }
            return write(exchange, HttpStatus.CONFLICT, b);
        }
        if (e instanceof ResponseStatusException rse) {
            return write(exchange, rse.getStatusCode(), body(rse.getReason()));
        }
        if (e instanceof TooManyRequestsException tm) {
            return tooManyRequests(exchange, tm);
        }
        String correlationId = traceId(exchange);
        log.error("generated route failed [{}]", correlationId, e);
        return write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, unexpected(correlationId));
    }

    /** Over the team's quota: 429 with {@code Retry-After}. */
    private Mono<Void> tooManyRequests(ServerWebExchange exchange, TooManyRequestsException e) {
        Map<String, Object> b = body(e.getMessage());
        b.put("retryAfter", e.retryAfterSeconds());
        exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        return write(exchange, HttpStatus.TOO_MANY_REQUESTS, b);
    }

    private static Map<String, Object> body(String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", error == null ? "unexpected error" : error);
        return m;
    }

    /** A 500 says nothing of its cause (a message may name a host, a path or a query); the id finds it in the log. */
    private static Map<String, Object> unexpected(String correlationId) {
        Map<String, Object> m = body("unexpected error");
        m.put("correlationId", correlationId);
        return m;
    }

    private Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, Object body) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        byte[] bytes;
        try {
            bytes = json.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            log.error("cannot serialise response", e);
            bytes = "{\"error\":\"cannot serialise response\"}".getBytes(StandardCharsets.UTF_8);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setContentLength(bytes.length);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
