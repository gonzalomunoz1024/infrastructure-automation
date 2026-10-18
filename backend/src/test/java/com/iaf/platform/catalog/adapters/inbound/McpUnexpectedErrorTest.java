package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.catalog.domain.JsonRpc;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.adapters.outbound.InMemoryIdempotencyStore;
import com.iaf.platform.gateway.application.IdempotencyHandler;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** An unexpected failure of an MCP call answers -32603 without its cause, as a REST 500 does. */
class McpUnexpectedErrorTest {

    static final ObjectMapper JSON = SampleCatalog.json();

    @Test
    void anUnexpectedFailureNamesNoCauseAndGivesACorrelationId() throws Exception {
        WorkflowQueries workflows = mock(WorkflowQueries.class);
        ResourceQueries resources = mock(ResourceQueries.class);
        RunsCommands runs = mock(RunsCommands.class);
        PolicyPort policy = mock(PolicyPort.class);
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.empty());
        when(runs.startJob(any())).thenReturn(Mono.error(new IllegalStateException("connection to db.internal.example:5432 refused")));
        CatalogQueryHandler queries = new CatalogQueryHandler(workflows, resources);
        McpServer mcp = new McpServer(queries, new WorkflowInvoker(runs, policy), resources, mock(RunsQueries.class), runs,
                mock(AssistantCommands.class), JSON, new IdempotencyHandler(new InMemoryIdempotencyStore(100), Duration.ofHours(24), Clock.systemUTC()));

        byte[] body = WebTestClient.bindToController(new RestControllerCatalogAdapter(queries, mcp)).controllerAdvice(new GlobalExceptionHandler())
                .webFilter(McpIdempotencyTest.identity(SampleCatalog.AGENT)).build()
                .post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\",\"reason\":\"stuck\"}}}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

        JsonNode error = JSON.readTree(body).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(JsonRpc.INTERNAL_ERROR);
        assertThat(error.path("message").asText()).isEqualTo("unexpected error");
        assertThat(error.path("data").path("correlationId").asText()).matches("[0-9a-f]{32}");
        assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("db.internal");
    }
}
