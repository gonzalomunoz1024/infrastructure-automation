package com.iaf.platform.catalog.adapters.inbound;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.domain.CatalogEntry;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.catalog.domain.RouteTable;
import com.iaf.platform.catalog.ports.inbound.CatalogQueries;
import com.iaf.platform.identity.application.CurrentIdentity;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The fixed endpoints of the generated surface: the catalog, the OpenAPI document and the MCP
 * server. The generated operations themselves are served by {@link GeneratedRouteWebFilter}.
 */
@RestController
@RequiredArgsConstructor
public class RestControllerCatalogAdapter implements CatalogQueries {

    public static final String MCP_PATH = "/api/v1/mcp";

    private final CatalogQueryHandler queries;
    private final McpServer mcp;

    @Override
    @GetMapping("/api/v1/catalog")
    public Flux<CatalogEntry> entries() {
        return queries.entries();
    }

    @Override
    @GetMapping(value = {"/api/v1/openapi.json", "/openapi.json"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<JsonNode> openApi() {
        return queries.openApi();
    }

    @Override
    public Mono<RouteTable> routes() {
        return queries.routes();
    }

    @Override
    public Mono<List<McpTools.Tool>> tools() {
        return queries.tools();
    }

    /** JSON-RPC 2.0, one message (or a batch of them) per POST; 202 without a body for notifications. */
    @PostMapping(value = MCP_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<JsonNode>> mcp(@RequestBody(required = false) String body) {
        return CurrentIdentity.current()
                .flatMap(who -> mcp.post(body, who))
                .map(reply -> reply.body() == null
                        ? ResponseEntity.status(reply.status()).<JsonNode>build()
                        : ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body()));
    }

    /** The server never streams: there is no SSE channel to open with a GET. */
    @RequestMapping(value = MCP_PATH, method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<Map<String, Object>> mcpNotAllowed() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header("Allow", "POST")
                .body(Map.of("error", "The MCP endpoint takes JSON-RPC 2.0 messages by POST; it does not stream"));
    }
}
