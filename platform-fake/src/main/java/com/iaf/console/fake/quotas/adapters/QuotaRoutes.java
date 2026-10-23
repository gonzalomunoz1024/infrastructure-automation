package com.iaf.console.fake.quotas.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.PUT;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.quotas.application.Quotas;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.domain.CommandResult;

import reactor.core.publisher.Mono;

/** The quota routes (P-10): read a bucket, set an override. */
public final class QuotaRoutes {

    private final Quotas quotas;
    private final Commands commands;

    public QuotaRoutes(Quotas quotas, Commands commands) {
        this.quotas = quotas;
        this.commands = commands;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(GET("/api/v1/quotas/{key}"), this::get)
                .andRoute(PUT("/api/v1/quotas/{key}"), this::put);
    }

    private Mono<ServerResponse> get(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.JOBS_READ);
        String key = request.pathVariable("key");
        if (!Quotas.mayRead(caller, key)) {
            throw ApiException.forbidden("a console account may read its own quota only");
        }
        return Http.ok(quotas.get(key));
    }

    private Mono<ServerResponse> put(ServerRequest request) {
        Access.requireAdmin(Callers.of(request));
        String key = request.pathVariable("key");
        return commands.handle(request, body -> CommandResult.ok(quotas.put(key, body)));
    }
}
