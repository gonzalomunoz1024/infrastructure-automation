package com.iaf.console.fake.content.adapters;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.Http;
import com.iaf.console.fake.common.Json;
import com.iaf.console.fake.content.application.ContentStore;
import com.iaf.console.fake.content.domain.PriceList;
import com.iaf.console.fake.content.domain.RegionDef;
import com.iaf.console.fake.content.domain.ResourceTypeDef;
import com.iaf.console.fake.content.domain.WorkflowDef;
import com.iaf.console.fake.identity.application.Access;
import com.iaf.console.fake.identity.application.Callers;

import reactor.core.publisher.Mono;

/** The content routes: resource types with ETags, the catalog, the regions, the price lists (P-22) and the OpenAPI document. */
public final class ContentRoutes {

    private final ContentStore content;

    public ContentRoutes(ContentStore content) {
        this.content = content;
    }

    public RouterFunction<ServerResponse> routes() {
        return route(GET("/api/v1/resource-types"), this::listTypes)
                .andRoute(GET("/api/v1/resource-types/{name}"), this::getType)
                .andRoute(GET("/api/v1/catalog"), this::catalog)
                .andRoute(GET("/api/v1/regions"), this::listRegions)
                .andRoute(GET("/api/v1/regions/{name}"), this::getRegion)
                .andRoute(GET("/api/v1/regions/{name}/availability"), this::availability)
                .andRoute(GET("/api/v1/price-lists"), this::listPriceLists)
                .andRoute(GET("/api/v1/price-lists/{name}"), this::getPriceList)
                .andRoute(GET("/api/v1/openapi.json"), this::openApi);
    }

    private Mono<ServerResponse> listTypes(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String etag = content.typesEtag();
        if (matches(request, etag)) {
            return ServerResponse.status(304).header(HttpHeaders.ETAG, etag).build();
        }
        List<ObjectNode> types = new ArrayList<>();
        for (ResourceTypeDef type : content.types()) {
            types.add(type.document());
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).bodyValue(types);
    }

    private Mono<ServerResponse> getType(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String name = request.pathVariable("name");
        ResourceTypeDef type = content.type(name).orElseThrow(() -> ApiException.notFound("resource type", name));
        String etag = "\"" + type.hash() + "\"";
        if (matches(request, etag)) {
            return ServerResponse.status(304).header(HttpHeaders.ETAG, etag).build();
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).bodyValue(type.document());
    }

    /** P-22: every published price list, one page, with an ETag that changes when any does. */
    private Mono<ServerResponse> listPriceLists(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String etag = content.priceListsEtag();
        if (matches(request, etag)) {
            return ServerResponse.status(304).header(HttpHeaders.ETAG, etag).build();
        }
        List<ObjectNode> lists = new ArrayList<>();
        for (PriceList list : content.priceLists()) {
            lists.add(list.document());
        }
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).bodyValue(lists);
    }

    private Mono<ServerResponse> getPriceList(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String name = request.pathVariable("name");
        PriceList list = content.priceList(name).orElseThrow(() -> ApiException.notFound("price list", name));
        return Http.ok(list.document());
    }

    private static boolean matches(ServerRequest request, String etag) {
        for (String value : request.headers().header(HttpHeaders.IF_NONE_MATCH)) {
            for (String candidate : value.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.equals("*") || trimmed.equals(etag) || trimmed.equals("W/" + etag) || ("\"" + trimmed + "\"").equals(etag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Mono<ServerResponse> catalog(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        return Http.ok(content.catalog(Http.query(request, "noun").orElse(null)));
    }

    private Mono<ServerResponse> listRegions(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        List<ObjectNode> regions = new ArrayList<>();
        for (RegionDef region : content.regions()) {
            regions.add(region.document());
        }
        return Http.ok(regions);
    }

    private Mono<ServerResponse> getRegion(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String name = request.pathVariable("name");
        return Http.ok(content.region(name).orElseThrow(() -> ApiException.notFound("region", name)).document());
    }

    private Mono<ServerResponse> availability(ServerRequest request) {
        Access.requireScope(Callers.of(request), "registry:read");
        String name = request.pathVariable("name");
        content.region(name).orElseThrow(() -> ApiException.notFound("region", name));
        ObjectNode out = Json.object();
        var types = out.putArray("types");
        for (ResourceTypeDef type : content.types()) {
            types.add(type.name());
        }
        var workflows = out.putArray("workflows");
        for (WorkflowDef workflow : content.workflows()) {
            workflows.add(workflow.name());
        }
        return Http.ok(out);
    }

    private Mono<ServerResponse> openApi(ServerRequest request) {
        JsonNode document = content.openApi();
        return Http.ok(document);
    }
}
