package com.iaf.console.bff.demo.state.adapters.inbound;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import com.iaf.console.bff.demo.identity.application.DemoCaller;
import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.demo.state.application.DemoStateStore;
import com.iaf.console.bff.resources.application.ResourcesConfiguration;
import com.iaf.console.bff.resources.application.StateProperties;
import com.iaf.console.bff.resources.domain.StateQuery;
import com.iaf.console.bff.resources.domain.StateResource;
import com.iaf.console.bff.resources.domain.Tombstone;
import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * The state service's contract, served in process for the demo under {@code /demo-state}: the
 * list, one resource with its ETag and tombstone, its log, and the commands the BFF sends
 * (mint, request-update, request-delete, tag) with {@code Idempotency-Key} replays. The caller
 * is the BFF's internal JWT, read as the real service reads it: a user acts in the token's
 * account, the console itself in any.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
public class DemoStateRoutes {

    private static final String PREFIX = ResourcesConfiguration.DEMO_PATH;
    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String CONSOLE_REQUEST_ID = "X-Console-Request-Id";

    private final DemoStateStore store;
    private final DemoTokens tokens;
    private final String audience;

    public DemoStateRoutes(DemoStateStore store, DemoTokens tokens, StateProperties state) {
        this.store = store;
        this.tokens = tokens;
        this.audience = state.serviceName();
    }

    @Bean
    public RouterFunction<ServerResponse> demoStateRouter() {
        return route(GET(PREFIX + "/v1/resources"), this::list)
                .andRoute(POST(PREFIX + "/v1/resources:mint"), this::mint)
                .andRoute(GET(PREFIX + "/v1/wfrn/{wfrn}/events"), this::events)
                .andRoute(GET(PREFIX + "/v1/wfrn/{wfrn}"), this::get)
                .andRoute(POST(PREFIX + "/v1/wfrn/{wfrn}:request-update"), r -> command(r, (c, body) -> store.requestUpdate(c.accountId(), r.pathVariable("wfrn"), body, c.subject(), key(r), requestId(r))))
                .andRoute(POST(PREFIX + "/v1/wfrn/{wfrn}:request-delete"), r -> command(r, (c, body) -> store.requestDelete(c.accountId(), r.pathVariable("wfrn"), body, c.subject(), key(r), requestId(r))))
                .andRoute(POST(PREFIX + "/v1/wfrn/{wfrn}:tag"), r -> command(r, (c, body) -> store.tag(c.accountId(), r.pathVariable("wfrn"), body, c.subject(), key(r), requestId(r))));
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        DemoCaller c = caller.get();
        String accountId = request.queryParam("accountId").orElse(c.accountId());
        if (accountId == null) {
            return error(HttpStatus.BAD_REQUEST, "accountId is required for the console-internal principal");
        }
        if (!c.internal() && !accountId.equals(c.accountId())) {
            return error(HttpStatus.NOT_FOUND, "account " + accountId + " not found");
        }
        Map<String, String> tags = new LinkedHashMap<>();
        request.queryParams().forEach((name, values) -> {
            if (name.startsWith("tag.") && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        Integer limit = request.queryParam("limit").map(Integer::valueOf).orElse(null);
        Boolean drift = request.queryParam("drift").map(Boolean::valueOf).orElse(null);
        StateQuery query = new StateQuery(request.queryParam("type").orElse(null), request.queryParam("state").orElse(null),
                request.queryParam("name").orElse(null), tags, request.queryParam("wfrn").orElse(null), drift,
                request.queryParam("sort").orElse(null), limit, request.queryParam("after").orElse(null));
        return answer(() -> {
            Page<StateResource> page = store.list(accountId, request.queryParam("region").orElse(null), query);
            return json(HttpStatus.OK, pageOf(page.items(), page.next()));
        });
    }

    private Mono<ServerResponse> mint(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        DemoCaller c = caller.get();
        if (c.internal() || c.accountId() == null) {
            return json(HttpStatus.BAD_REQUEST, Map.of("error", "a resource is minted for an account", "problems",
                    List.of(Map.of("level", "error", "code", "account.required", "message", "the caller acts for no account"))));
        }
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(JsonNodeFactory.instance.objectNode())
                .flatMap(body -> store.mint(c.accountId(), body.path("region").asText("global"), body, c.subject(), key(request), requestId(request)))
                .flatMap(a -> answered(HttpStatus.CREATED, a))
                .onErrorResume(this::failure);
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        String wfrn = request.pathVariable("wfrn");
        Optional<Object> found = store.get(caller.get().internal() ? null : caller.get().accountId(), wfrn);
        if (found.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "resource " + wfrn + " not found");
        }
        if (found.get() instanceof Tombstone tombstone) {
            return json(HttpStatus.GONE, tombstone);
        }
        StateResource resource = (StateResource) found.get();
        String etag = "\"" + resource.version() + "\"";
        if (etag.equals(request.headers().firstHeader(HttpHeaders.IF_NONE_MATCH))) {
            return ServerResponse.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).bodyValue(resource);
    }

    private Mono<ServerResponse> events(ServerRequest request) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        String wfrn = request.pathVariable("wfrn");
        long after;
        try {
            after = request.queryParam("after").filter(s -> !s.isBlank()).map(Long::parseLong).orElse(0L);
        } catch (NumberFormatException e) {
            return error(HttpStatus.BAD_REQUEST, "after is a version number on a resource's log");
        }
        int limit = request.queryParam("limit").map(Integer::valueOf).map(l -> Math.max(1, Math.min(200, l))).orElse(50);
        return store.events(caller.get().internal() ? null : caller.get().accountId(), wfrn, limit, after)
                .map(page -> json(HttpStatus.OK, pageOf(page.items(), page.next())))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "resource " + wfrn + " not found"));
    }

    private interface Command {
        DemoStateStore.Answer run(DemoCaller caller, JsonNode body);
    }

    private Mono<ServerResponse> command(ServerRequest request, Command command) {
        Optional<DemoCaller> caller = caller(request);
        if (caller.isEmpty()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid or missing token");
        }
        DemoCaller c = caller.get();
        return request.bodyToMono(JsonNode.class).defaultIfEmpty(JsonNodeFactory.instance.objectNode())
                .flatMap(body -> answer(() -> answered(HttpStatus.OK, command.run(new DemoCaller(c.subject(), c.internal() ? null : c.accountId(), c.service(), c.groups()), body))));
    }

    // ---- pieces

    private Optional<DemoCaller> caller(ServerRequest request) {
        return tokens.verify(request.headers().firstHeader(HttpHeaders.AUTHORIZATION), audience);
    }

    private static String key(ServerRequest request) {
        return request.headers().firstHeader(IDEMPOTENCY_KEY);
    }

    private static String requestId(ServerRequest request) {
        return request.headers().firstHeader(CONSOLE_REQUEST_ID);
    }

    private static Mono<ServerResponse> answered(HttpStatus status, DemoStateStore.Answer answer) {
        ServerResponse.BodyBuilder builder = ServerResponse.status(answer.replayed() ? HttpStatus.OK : status).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ETAG, "\"" + answer.resource().version() + "\"");
        if (answer.replayed()) {
            builder = builder.header("Idempotent-Replay", "true");
        }
        return builder.bodyValue(answer.resource());
    }

    private Mono<ServerResponse> answer(java.util.function.Supplier<Mono<ServerResponse>> work) {
        try {
            return work.get().onErrorResume(this::failure);
        } catch (RuntimeException e) {
            return failure(e);
        }
    }

    private Mono<ServerResponse> failure(Throwable e) {
        if (e instanceof NotFoundException) {
            return error(HttpStatus.NOT_FOUND, e.getMessage());
        }
        if (e instanceof ConflictException conflict) {
            return json(HttpStatus.CONFLICT, body(e.getMessage(), conflict.detail()));
        }
        if (e instanceof InvalidInputException invalid) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", e.getMessage());
            body.put("problems", invalid.problems());
            return json(HttpStatus.BAD_REQUEST, body);
        }
        if (e instanceof IllegalArgumentException) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return Mono.error(e);
    }

    private static Map<String, Object> body(String error, Object detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        if (detail != null) {
            body.put("detail", detail);
        }
        return body;
    }

    private static Map<String, Object> pageOf(List<?> items, String next) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("next", next);
        return page;
    }

    private static Mono<ServerResponse> json(HttpStatus status, Object body) {
        return ServerResponse.status(status).contentType(MediaType.APPLICATION_JSON).bodyValue(body);
    }

    private static Mono<ServerResponse> error(HttpStatus status, String message) {
        return json(status, Map.of("error", message));
    }

}
