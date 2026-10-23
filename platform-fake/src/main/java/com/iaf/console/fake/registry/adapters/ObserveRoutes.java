package com.iaf.console.fake.registry.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.registry.application.Observer;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.registry.domain.ResourceRecord;

import reactor.core.publisher.Mono;

/** The observe routes (P-09): one resource, a batch, a type's paged read and discovery, and metrics. */
public final class ObserveRoutes {

    private final Observer observer;
    private final Registry registry;
    private final ContentStore content;

    public ObserveRoutes(Observer observer, Registry registry, ContentStore content) {
        this.observer = observer;
        this.registry = registry;
        this.content = content;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(POST("/api/v1/resources/by-wfrn:observe"), this::observeByWfrn)
                .andRoute(POST("/api/v1/resources:observe"), this::observeMany)
                .andRoute(POST("/api/v1/resources/{id}:observe"), this::observe)
                .andRoute(POST("/api/v1/resources/{id}:metrics"), this::metrics)
                .andRoute(POST("/api/v1/resource-types/{name}:observe-list"), request -> page(request, true))
                .andRoute(POST("/api/v1/resource-types/{name}:discover"), request -> page(request, false));
    }

    private Mono<ServerResponse> observe(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.RESOURCES_OBSERVE);
        String id = request.pathVariable("id");
        ResourceRecord record = registry.visible(id, caller).orElseThrow(() -> ApiException.notFound("resource", id));
        return Http.ok(observer.observe(record, caller));
    }

    private Mono<ServerResponse> observeByWfrn(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.RESOURCES_OBSERVE);
        String wfrn = ResourceRoutes.wfrnParameter(request);
        ResourceRecord record = registry.byWfrn(wfrn).filter(r -> Registry.sees(caller, r)).orElseThrow(() -> ApiException.notFound("resource", wfrn));
        return Http.ok(observer.observe(record, caller));
    }

    private Mono<ServerResponse> observeMany(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.RESOURCES_OBSERVE);
        return body(request).flatMap(body -> Http.ok(observer.observeMany(body, caller)));
    }

    private Mono<ServerResponse> metrics(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.RESOURCES_OBSERVE);
        String id = request.pathVariable("id");
        ResourceRecord record = registry.visible(id, caller).orElseThrow(() -> ApiException.notFound("resource", id));
        return body(request).flatMap(body -> Http.ok(observer.metrics(record, body, caller)));
    }

    private Mono<ServerResponse> page(ServerRequest request, boolean managedOnly) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.RESOURCES_OBSERVE);
        String name = request.pathVariable("name");
        ResourceTypeDef type = content.type(name).orElseThrow(() -> ApiException.notFound("resource type", name));
        return body(request).flatMap(body -> Http.ok(observer.page(type, body, caller, managedOnly)));
    }

    private static Mono<JsonNode> body(ServerRequest request) {
        return request.bodyToMono(String.class).defaultIfEmpty("").map(text -> {
            if (text.isBlank()) {
                return Json.object();
            }
            try {
                return Json.read(text);
            } catch (RuntimeException e) {
                throw ApiException.badRequest("body.invalidJson");
            }
        });
    }
}
