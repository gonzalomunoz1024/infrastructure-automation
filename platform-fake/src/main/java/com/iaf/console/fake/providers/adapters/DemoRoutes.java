package com.iaf.console.fake.providers.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.providers.domain.RecordedWorld;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.domain.CommandResult;

/** The demo route of P-09: {@code POST /api/v1/demo/world:mutate} changes what the recorded providers answer. */
public final class DemoRoutes {

    private final RecordedWorld world;
    private final Commands commands;

    public DemoRoutes(RecordedWorld world, Commands commands) {
        this.world = world;
        this.commands = commands;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(POST("/api/v1/demo/world:mutate"), request -> {
            Callers.of(request);
            return commands.handle(request, body -> CommandResult.ok(world.mutate(body)));
        });
    }
}
