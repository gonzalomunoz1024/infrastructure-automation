package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.adapters.outbound.InMemoryIdempotencyStore;
import com.iaf.platform.gateway.application.IdempotencyHandler;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** {@code idempotencyKey} on MCP tools/call. */
class McpIdempotencyTest {

    static final ObjectMapper JSON = SampleCatalog.json();

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    AtomicInteger started = new AtomicInteger();
    CatalogQueryHandler queries = new CatalogQueryHandler(workflows, resources);
    McpServer mcp = new McpServer(queries, new WorkflowInvoker(runs, policy), resources, mock(RunsQueries.class), runs, mock(AssistantCommands.class), JSON,
            new IdempotencyHandler(new InMemoryIdempotencyStore(100), Duration.ofHours(24), Clock.systemUTC()));

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.empty());
        when(runs.startJob(any())).thenAnswer(inv -> Mono.fromSupplier(() -> CommandAccepted.of("job_" + started.incrementAndGet(), "queued")));
        when(runs.startBatch(any())).thenAnswer(inv -> Mono.fromSupplier(() -> CommandAccepted.of("job_b" + started.incrementAndGet(), "queued")));
    }

    static WebFilter identity(Identity who) {
        return (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
        };
    }

    EntityExchangeResult<byte[]> call(Identity who, String tool, String arguments) {
        return WebTestClient.bindToController(new RestControllerCatalogAdapter(queries, mcp)).controllerAdvice(new GlobalExceptionHandler())
                .webFilter(identity(who)).build()
                .post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}")
                .exchange().expectStatus().isOk().expectBody().returnResult();
    }

    static JsonNode json(EntityExchangeResult<byte[]> r) {
        try {
            return JSON.readTree(r.getResponseBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theSameKeyAndArgumentsReturnTheFirstResultMarkedAsAReplay() {
        EntityExchangeResult<byte[]> first = call(SampleCatalog.AGENT, "vm_restart", "{\"vmId\":\"vm-1\",\"reason\":\"stuck\",\"idempotencyKey\":\"r-1\"}");
        EntityExchangeResult<byte[]> again = call(SampleCatalog.AGENT, "vm_restart", "{\"reason\":\"stuck\",\"idempotencyKey\":\"r-1\",\"vmId\":\"vm-1\"}");

        JsonNode a = json(first).path("result");
        JsonNode b = json(again).path("result");
        assertThat(a.path("structuredContent").path("jobId").asText()).isEqualTo("job_1");
        assertThat(a.has("_meta")).isFalse();
        assertThat(first.getResponseHeaders().getFirst("Idempotent-Replay")).isNull();
        assertThat(b.path("structuredContent").path("jobId").asText()).isEqualTo("job_1");
        assertThat(b.path("_meta").path(McpServer.REPLAY_META).asBoolean()).isTrue();
        assertThat(again.getResponseHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

        ArgumentCaptor<StartJob> job = ArgumentCaptor.forClass(StartJob.class);
        verify(runs, times(1)).startJob(job.capture());
        assertThat(job.getValue().input().has("idempotencyKey")).isFalse();
    }

    @Test
    void theSameKeyWithOtherArgumentsIsAConflict() {
        call(SampleCatalog.AGENT, "vm_restart", "{\"vmId\":\"vm-1\",\"idempotencyKey\":\"r-1\"}");
        JsonNode error = json(call(SampleCatalog.AGENT, "vm_restart", "{\"vmId\":\"vm-2\",\"idempotencyKey\":\"r-1\"}")).path("error");
        assertThat(error.path("message").asText()).contains("different request");
        assertThat(error.path("data").path("conflict").asBoolean()).isTrue();
        verify(runs, times(1)).startJob(any());
    }

    @Test
    void batchToolsTakeAKeyAndOtherCallersHaveTheirOwnKeys() {
        String args = "{\"items\":[{\"vmId\":\"vm-1\"}],\"idempotencyKey\":\"b-1\"}";
        call(SampleCatalog.AGENT, "vm_restart_batch", args);
        call(SampleCatalog.AGENT, "vm_restart_batch", args);
        call(SampleCatalog.OPS, "vm_restart_batch", args);
        verify(runs, times(2)).startBatch(any());
    }

    @Test
    void workflowToolsAdvertiseTheKeyUnlessTheirInputHasSuchAField() {
        List<McpTools.Tool> tools = McpTools.of(SampleCatalog.workflows(), SampleCatalog.types());
        McpTools.Tool restart = McpTools.find(tools, "vm_restart").orElseThrow();
        McpTools.Tool batch = McpTools.find(tools, "vm_restart_batch").orElseThrow();
        assertThat(restart.inputSchema().path("properties").path("idempotencyKey").path("type").asText()).isEqualTo("string");
        assertThat(batch.inputSchema().path("properties").has("idempotencyKey")).isTrue();
        assertThat(restart.inputSchema().path("required")).extracting(JsonNode::asText).doesNotContain("idempotencyKey");
        assertThat(McpTools.isCallArgument(restart, "idempotencyKey")).isTrue();
        assertThat(McpTools.isCallArgument(restart, "vmId")).isFalse();
        assertThat(McpTools.isCallArgument(McpTools.find(tools, "get_job_status").orElseThrow(), "idempotencyKey")).isFalse();
    }
}
