package com.iaf.console.fake.registry.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;
import com.iaf.console.fake.identity.domain.Caller;
import com.iaf.console.fake.identity.domain.Scopes;
import com.iaf.console.fake.registry.application.Registry;
import com.iaf.console.fake.registry.domain.ResourceRecord;
import com.iaf.console.fake.runs.application.Commands;
import com.iaf.console.fake.runs.domain.CommandResult;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.wfrn.Wfrn;
import com.iaf.console.wfrn.WfrnException;

import reactor.core.publisher.Mono;

/** The registry routes (P-05, P-18): list with filters and paging, get by id or wfrn, lineage, the backfill and the collision report, plus GET /api/v1/{plural}. */
public final class ResourceRoutes {

    private final Registry registry;
    private final ContentStore content;
    private final Commands commands;

    public ResourceRoutes(Registry registry, ContentStore content, Commands commands) {
        this.registry = registry;
        this.content = content;
        this.commands = commands;
    }

    public RouterFunction<ServerResponse> routes() {
        RouterFunction<ServerResponse> routes = route(GET("/api/v1/resources"), request -> list(request, null))
                .andRoute(GET("/api/v1/resources/by-wfrn"), this::byWfrn)
                .andRoute(GET("/api/v1/resources/{id}"), request -> get(request, null))
                .andRoute(GET("/api/v1/resources/{id}/lineage"), this::lineage)
                .andRoute(POST("/api/v1/resources:backfill-wfrn"), this::backfill)
                .andRoute(POST("/api/v1/resources:find-collisions"), this::collisions);
        for (ResourceTypeDef type : content.types()) {
            String typeName = type.name();
            routes = routes.andRoute(GET("/api/v1/" + type.plural()), request -> list(request, typeName))
                    .andRoute(GET("/api/v1/" + type.plural() + "/{id}"), request -> get(request, typeName));
        }
        return routes;
    }

    private Mono<ServerResponse> list(ServerRequest request, String fixedType) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.REGISTRY_READ);
        String account = Http.query(request, "account").orElse(null);
        if (account != null && !AccountScope.isAccountId(account)) {
            throw ApiException.badRequest("account must be a 12-digit account id");
        }
        Registry.Filter filter = new Registry.Filter(fixedType != null ? fixedType : Http.query(request, "type").orElse(null),
                Http.query(request, "owner").orElse(null), Http.query(request, "status").orElse(null), account,
                Http.query(request, "wfrn").orElse(null), Http.query(request, "visibility").orElse(null));
        Optional<Integer> limit = Http.limit(request, Registry.MAX_LIMIT);
        if (limit.isEmpty()) {
            return Http.ok(documents(registry.list(filter, caller)));
        }
        Registry.Page page = registry.page(filter, caller, limit.get(), Http.query(request, "after").orElse(null));
        ServerResponse.BodyBuilder builder = ServerResponse.ok().contentType(MediaType.APPLICATION_JSON);
        if (page.next() != null) {
            builder = builder.header(HttpHeaders.LINK, Http.nextLink(request, page.next()));
        }
        return builder.bodyValue(documents(page.items()));
    }

    private static List<ObjectNode> documents(List<ResourceRecord> records) {
        List<ObjectNode> out = new ArrayList<>();
        for (ResourceRecord record : records) {
            out.add(record.document());
        }
        return out;
    }

    private Mono<ServerResponse> get(ServerRequest request, String fixedType) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.REGISTRY_READ);
        String id = request.pathVariable("id");
        ResourceRecord record = registry.visible(id, caller).filter(r -> fixedType == null || fixedType.equals(r.type()))
                .orElseThrow(() -> ApiException.notFound("resource", id));
        return Http.ok(record.document());
    }

    private Mono<ServerResponse> byWfrn(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.REGISTRY_READ);
        String wfrn = wfrnParameter(request);
        ResourceRecord record = registry.byWfrn(wfrn).filter(r -> Registry.sees(caller, r)).orElseThrow(() -> ApiException.notFound("resource", wfrn));
        return Http.ok(record.document());
    }

    /** The {@code wfrn} query parameter, parsed so a malformed name is a 400. */
    public static String wfrnParameter(ServerRequest request) {
        String wfrn = Http.query(request, "wfrn").orElseThrow(() -> ApiException.badRequest("wfrn is required"));
        try {
            return Wfrn.parse(wfrn).format();
        } catch (WfrnException e) {
            throw ApiException.badRequest("wfrn.invalid: " + e.getMessage());
        }
    }

    private Mono<ServerResponse> lineage(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireScope(caller, Scopes.REGISTRY_READ);
        String id = request.pathVariable("id");
        ResourceRecord record = registry.visible(id, caller).orElseThrow(() -> ApiException.notFound("resource", id));
        return Http.ok(registry.lineage(record, caller));
    }

    private Mono<ServerResponse> backfill(ServerRequest request) {
        Caller caller = Callers.of(request);
        Access.requireAdminOrScope(caller, Scopes.RESOURCES_BACKFILL);
        return commands.handle(request, body -> {
            String accountId = body.path("accountId").asText(null);
            String region = body.path("region").asText(null);
            String owner = body.path("owner").asText(null);
            if (accountId == null || !AccountScope.isAccountId(accountId)) {
                throw ApiException.badRequest("accountId must be a 12-digit account id");
            }
            if (region == null || content.region(region).isEmpty()) {
                throw ApiException.badRequest("region must name a region");
            }
            if (owner == null || owner.isBlank()) {
                throw ApiException.badRequest("owner is required");
            }
            return CommandResult.ok(registry.backfill(accountId, region, owner, caller));
        });
    }

    private Mono<ServerResponse> collisions(ServerRequest request) {
        Access.requireAdmin(Callers.of(request));
        return Http.ok(registry.collisions());
    }
}
