package com.iaf.console.search.support;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.Json;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * The state service's two reads over HTTP, as its contract serves them, from a {@link FakeStateService}:
 * {@code GET /v1/resources} (paged, oldest change first with {@code sort=updatedAt}) and
 * {@code GET /v1/wfrn/{wfrn}} (200, 410 with the tombstone, 404). A caller must name itself with
 * the dev header {@code X-Console-Service} or a bearer token it was not told to refuse, else 401.
 */
public final class StubStateServer implements AutoCloseable {

    private final FakeStateService data;
    private final DisposableServer server;
    private final AtomicInteger unauthorized = new AtomicInteger();
    private final Set<String> refused = ConcurrentHashMap.newKeySet();
    private final List<String> bearers = new CopyOnWriteArrayList<>();

    private StubStateServer(FakeStateService data) {
        this.data = data;
        var router = route(GET("/v1/resources"), this::list).andRoute(GET("/v1/wfrn/{wfrn}"), this::one);
        var adapter = new ReactorHttpHandlerAdapter(RouterFunctions.toHttpHandler(router));
        this.server = HttpServer.create().host("127.0.0.1").port(0).handle(adapter).bindNow();
    }

    public static StubStateServer start() {
        return new StubStateServer(new FakeStateService());
    }

    public FakeStateService data() {
        return data;
    }

    public String url() {
        return "http://127.0.0.1:" + server.port();
    }

    public int unauthorized() {
        return unauthorized.get();
    }

    /** Answers 401 to this bearer token from now on, as for a revoked one. */
    public void refuse(String token) {
        refused.add(token);
    }

    /** The bearer tokens callers sent, in order. */
    public List<String> bearers() {
        return List.copyOf(bearers);
    }

    private boolean authenticated(ServerRequest request) {
        String authorization = request.headers().firstHeader("Authorization");
        String bearer = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (bearer != null) {
            bearers.add(bearer);
        }
        boolean ok = bearer != null ? !refused.contains(bearer)
                : request.headers().firstHeader("X-Console-Service") != null || authorization != null;
        if (!ok) {
            unauthorized.incrementAndGet();
        }
        return ok;
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        if (!authenticated(request)) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
        }
        String account = request.queryParam("accountId").orElse(null);
        if (account == null) {
            return ServerResponse.badRequest().bodyValue(Json.object().put("error", "accountId is required for a call that acts for no account"));
        }
        int limit = request.queryParam("limit").map(Integer::parseInt).orElse(50);
        if (limit < 1 || limit > 200) {
            return ServerResponse.badRequest().bodyValue(Json.object().put("error", "limit is 1 to 200"));
        }
        int offset = request.queryParam("after").map(Integer::parseInt).orElse(0);
        Instant updatedAfter = request.queryParam("updatedAfter").map(Instant::parse).orElse(null);
        ResourceState state = request.queryParam("state").map(ResourceState::of).orElse(null);
        StateListing listing = new StateListing(updatedAfter, state, request.queryParam("type").orElse(null), limit);
        return data.list(AccountScope.of(account, "global", "stub"), listing).map(StateResource::json).collectList().flatMap(all -> {
            List<JsonNode> page = all.subList(Math.min(offset, all.size()), Math.min(offset + limit, all.size()));
            ObjectNode body = Json.object();
            ArrayNode items = body.putArray("items");
            page.forEach(items::add);
            if (offset + limit < all.size()) {
                body.put("next", String.valueOf(offset + limit));
            } else {
                body.putNull("next");
            }
            return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        });
    }

    private Mono<ServerResponse> one(ServerRequest request) {
        if (!authenticated(request)) {
            return ServerResponse.status(HttpStatus.UNAUTHORIZED).build();
        }
        String wfrn = request.pathVariable("wfrn");
        String account = wfrn.split(":").length > 4 ? wfrn.split(":")[4] : "000000000000";
        return data.find(AccountScope.of(account, "global", "stub"), wfrn)
                .flatMap(r -> r.tombstone()
                        ? ServerResponse.status(HttpStatus.GONE).contentType(MediaType.APPLICATION_JSON).bodyValue(r.json())
                        : ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).eTag("\"" + r.version() + "\"").bodyValue(r.json()))
                .switchIfEmpty(ServerResponse.status(HttpStatus.NOT_FOUND).bodyValue(Json.object().put("error", "resource not found")));
    }

    @Override
    public void close() {
        server.disposeNow();
    }
}
