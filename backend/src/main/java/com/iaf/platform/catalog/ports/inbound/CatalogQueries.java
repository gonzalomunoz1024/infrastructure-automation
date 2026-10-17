package com.iaf.platform.catalog.ports.inbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.domain.CatalogEntry;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.catalog.domain.RouteTable;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Read side of the generated surface. Everything is computed from the live catalog (the
 * published workflows and the resource types), so a publish changes the answers at once.
 */
public interface CatalogQueries {

    /** One entry per published workflow, by name. */
    Flux<CatalogEntry> entries();

    /** The OpenAPI 3.1 document of every published workflow and resource type. */
    Mono<JsonNode> openApi();

    /** The generated routes the gateway matches requests against. */
    Mono<RouteTable> routes();

    /** The MCP tools: workflows with {@code mcp.expose}, resource reads, jobs, the assistant. */
    Mono<List<McpTools.Tool>> tools();
}
