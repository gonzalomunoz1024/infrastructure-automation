package com.iaf.platform.catalog.application;

import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.domain.CatalogEntry;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.catalog.domain.OpenApiDocument;
import com.iaf.platform.catalog.domain.RouteTable;
import com.iaf.platform.catalog.ports.inbound.CatalogQueries;
import com.iaf.platform.resources.domain.ResourceTypeDefinition;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reads the published workflows and the resource types on every call and derives the catalog,
 * the routes, the OpenAPI document and the MCP tools from them. Nothing is cached: the sources
 * are in-memory projections, and recomputing is what keeps a publish visible without a restart.
 */
@Service
@Primary
@RequiredArgsConstructor
public class CatalogQueryHandler implements CatalogQueries {

    private final WorkflowQueries workflows;
    private final ResourceQueries resources;

    @Override
    public Flux<CatalogEntry> entries() {
        return published().flatMapIterable(list -> list.stream().map(r -> CatalogEntry.of(r, list)).toList());
    }

    @Override
    public Mono<JsonNode> openApi() {
        return Mono.zip(published(), types()).map(t -> OpenApiDocument.build(t.getT1(), t.getT2()));
    }

    @Override
    public Mono<RouteTable> routes() {
        return Mono.zip(published(), types()).map(t -> RouteTable.of(t.getT1(), t.getT2()));
    }

    @Override
    public Mono<List<McpTools.Tool>> tools() {
        return Mono.zip(published(), types()).map(t -> McpTools.of(t.getT1(), t.getT2()));
    }

    private Mono<List<WorkflowRecord>> published() {
        return workflows.publishedAll()
                .filter(r -> r.definition() != null)
                .collectSortedList(Comparator.comparing(WorkflowRecord::name, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private Mono<List<ResourceTypeDefinition>> types() {
        return resources.types().collectList();
    }
}
