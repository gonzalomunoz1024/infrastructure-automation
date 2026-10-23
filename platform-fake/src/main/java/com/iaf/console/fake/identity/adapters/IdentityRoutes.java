package com.iaf.console.fake.identity.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.DELETE;
import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.application.ServiceAccounts;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.domain.CommandResult;

import reactor.core.publisher.Mono;

/** The identity routes: who am I, and the service accounts (admins only). */
public final class IdentityRoutes {

    private final ServiceAccounts serviceAccounts;
    private final Commands commands;

    public IdentityRoutes(ServiceAccounts serviceAccounts, Commands commands) {
        this.serviceAccounts = serviceAccounts;
        this.commands = commands;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(GET("/api/v1/me"), this::me)
                .andRoute(GET("/api/v1/service-accounts"), this::list)
                .andRoute(POST("/api/v1/service-accounts"), this::create)
                .andRoute(POST("/api/v1/service-accounts/{id}:rotate"), this::rotate)
                .andRoute(DELETE("/api/v1/service-accounts/{id}"), this::delete);
    }

    private Mono<ServerResponse> me(ServerRequest request) {
        return Http.ok(Callers.of(request).document());
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        Access.requireAdmin(Callers.of(request));
        return Http.ok(serviceAccounts.list());
    }

    private Mono<ServerResponse> create(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireAdmin(caller);
        return commands.handle(request, body -> CommandResult.of(201, serviceAccounts.create(caller, body)));
    }

    private Mono<ServerResponse> rotate(ServerRequest request) {
        Access.requireAdmin(Callers.of(request));
        String id = request.pathVariable("id");
        return commands.handle(request, body -> CommandResult.of(201, serviceAccounts.rotate(id)));
    }

    private Mono<ServerResponse> delete(ServerRequest request) {
        Access.requireAdmin(Callers.of(request));
        String id = request.pathVariable("id");
        return commands.handle(request, body -> {
            serviceAccounts.delete(id);
            return CommandResult.noContent();
        });
    }
}
