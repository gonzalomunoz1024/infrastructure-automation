package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The catalog, the OpenAPI document and the MCP server over HTTP, with the other slices mocked. */
class RestControllerCatalogAdapterTest {

    static final ObjectMapper JSON = SampleCatalog.json();

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    com.iaf.platform.runs.ports.inbound.RunsQueries runsQueries = mock(com.iaf.platform.runs.ports.inbound.RunsQueries.class);
    PolicyPort policy = mock(PolicyPort.class);
    AssistantCommands assistant = mock(AssistantCommands.class);
    CatalogQueryHandler queries = new CatalogQueryHandler(workflows, resources);
    McpServer mcp = new McpServer(queries, new WorkflowInvoker(runs, policy), resources, runsQueries, runs, assistant, JSON);
    RestControllerCatalogAdapter adapter = new RestControllerCatalogAdapter(queries, mcp);

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.just(new Preview("vm-restart", "1.2.0", List.of(), List.of(), null, PolicyDecision.allow("mock"))));
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    static WebFilter identity(Identity who) {
        return (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
        };
    }

    WebTestClient client(Identity who) {
        return WebTestClient.bindToController(adapter).controllerAdvice(new GlobalExceptionHandler()).webFilter(identity(who)).build();
    }

    WebTestClient.ResponseSpec rpc(Identity who, String body) {
        return client(who).post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    JsonNode rpcResult(Identity who, String body) {
        byte[] bytes = rpc(who, body).expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            return JSON.readTree(bytes);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------- catalog and openapi

    @Test
    void servesTheCatalog() {
        client(SampleCatalog.OPS).get().uri("/api/v1/catalog").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(10)
                .jsonPath("$[?(@.workflow == 'vm-restart')].path").isEqualTo("/api/v1/vms/{vmId}:restart")
                .jsonPath("$[?(@.workflow == 'vm-restart')].batchPath").isEqualTo("/api/v1/vms:restart:batch")
                .jsonPath("$[?(@.workflow == 'vm-restart')].mcpTool").isEqualTo("vm_restart")
                .jsonPath("$[?(@.workflow == 'vm-restart')].batch.max").isEqualTo(500);
    }

    @Test
    void servesTheOpenApiDocumentOnBothPaths() {
        for (String path : List.of("/api/v1/openapi.json", "/openapi.json")) {
            client(SampleCatalog.OPS).get().uri(path).exchange()
                    .expectStatus().isOk()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                    .expectBody()
                    .jsonPath("$.openapi").isEqualTo("3.1.0")
                    .jsonPath("$.paths['/api/v1/vms/{vmId}:restart'].post.operationId").isEqualTo("vm_restart")
                    .jsonPath("$.paths['/api/v1/images'].get.operationId").isEqualTo("list_images");
        }
    }

    // ---------------------------------------------------------------- mcp

    @Test
    void initializeAnnouncesTheProtocolAndTools() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        assertThat(r.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(r.path("id").asInt()).isEqualTo(1);
        assertThat(r.path("result").path("protocolVersion").asText()).isEqualTo("2025-06-18");
        assertThat(r.path("result").path("capabilities").path("tools").path("listChanged").asBoolean()).isTrue();
        assertThat(r.path("result").path("serverInfo").path("name").asText()).isEqualTo("infrastructure-automation");
        assertThat(r.path("result").path("serverInfo").path("version").asText()).isNotBlank();
    }

    @Test
    void notificationsGetAnEmpty202AndPingAnEmptyResult() {
        rpc(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .expectStatus().isAccepted().expectBody().isEmpty();
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":\"p\",\"method\":\"ping\"}");
        assertThat(r.path("id").asText()).isEqualTo("p");
        assertThat(r.path("result").isObject()).isTrue();
        assertThat(r.path("result")).isEmpty();
    }

    @Test
    void toolsListHasTheGeneratedAndFixedTools() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<String> names = new java.util.ArrayList<>();
        r.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("vm_restart", "vm_restart_batch", "list_images", "get_images", "get_job_status", "cancel_job", "draft_workflow");
        JsonNode restart = r.path("result").path("tools").get(names.indexOf("vm_restart"));
        assertThat(restart.path("title").asText()).isEqualTo("VM restart");
        assertThat(restart.path("description").asText()).contains("Restart a virtual machine");
        assertThat(restart.path("inputSchema").path("required").get(0).asText()).isEqualTo("vmId");
    }

    @Test
    void toolsCallStartsAJobThroughTheSamePathAsTheGateway() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\",\"reason\":\"agent\"}}}");
        assertThat(r.has("error")).isFalse();
        JsonNode result = r.path("result");
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(result.path("content").get(0).path("text").asText()).contains("job_1");
        assertThat(result.path("structuredContent").path("jobId").asText()).isEqualTo("job_1");
        assertThat(result.path("structuredContent").path("links").path("job").asText()).isEqualTo("/api/v1/jobs/job_1");
        assertThat(result.has("isError")).isFalse();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        assertThat(captor.getValue().channel()).isEqualTo("mcp");
        assertThat(captor.getValue().requester().id()).isEqualTo("sa_agent");
        assertThat(captor.getValue().input().path("vmId").asText()).isEqualTo("vm-1");
        verify(policy).evaluate(any());
    }

    @Test
    void toolsCallBatchStartsABatch() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart_batch\",\"arguments\":{\"items\":[{\"vmId\":\"vm-1\"},{\"vmId\":\"vm-2\"}],\"defaults\":{\"reason\":\"fleet\"}}}}");
        assertThat(r.path("result").path("structuredContent").path("rows").asInt()).isEqualTo(2);
        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        assertThat(captor.getValue().items()).hasSize(2);
        assertThat(captor.getValue().channel()).isEqualTo("mcp");
    }

    @Test
    void invalidParamsCarryTheProblems() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"reason\":\"no id\"}}}");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(r.path("error").path("data").path("problems")).isNotEmpty();
        assertThat(r.path("error").path("data").path("problems").get(0).path("message").asText()).contains("vmId");
        verify(runs, never()).startJob(any());

        JsonNode unknown = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"no_such_tool\",\"arguments\":{}}}");
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(unknown.path("error").path("message").asText()).contains("no_such_tool");

        JsonNode missing = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"get_images\",\"arguments\":{}}}");
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(missing.path("error").path("data").path("problems").get(0).path("message").asText()).contains("id");
    }

    @Test
    void aServiceAccountCallsOnlyTheToolsItsScopesAllow() {
        WebFilter scoped = (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, SampleCatalog.AGENT);
            return chain.filter(exchange).contextWrite(ctx -> ServiceScopes.with(CurrentIdentity.with(ctx, SampleCatalog.AGENT), List.of("registry:read")));
        };
        WebTestClient reader = WebTestClient.bindToController(adapter).controllerAdvice(new GlobalExceptionHandler()).webFilter(scoped).build();
        byte[] list = reader.post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        byte[] call = reader.post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\"}}}").exchange()
                .expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            assertThat(JSON.readTree(list).path("result").path("tools")).isNotEmpty();
            JsonNode refused = JSON.readTree(call);
            assertThat(refused.path("error").path("data").path("forbidden").asBoolean()).isTrue();
            assertThat(refused.path("error").path("message").asText()).contains("jobs:start");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        verify(runs, never()).startJob(any());
        assertThat(McpServer.scopeOf(McpTools.find(queries.tools().block(), "get_job_status").orElseThrow())).isEqualTo("jobs:read");
        assertThat(McpServer.scopeOf(McpTools.find(queries.tools().block(), "draft_workflow").orElseThrow())).isEqualTo("workflows:write");
    }

    @Test
    void forbiddenAndPolicyDeniedHaveTheirOwnCodesWithAMessage() {
        JsonNode r = rpcResult(SampleCatalog.GUEST, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\"}}}");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(r.path("error").path("message").asText()).contains("may not invoke");
        assertThat(r.path("error").path("data").path("forbidden").asBoolean()).isTrue();
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("freeze", "deny", "Frozen")), "mock")));
        JsonNode denied = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\"}}}");
        assertThat(denied.path("error").path("code").asInt()).isEqualTo(-32002);
        assertThat(denied.path("error").path("message").asText()).contains("Frozen");
        assertThat(denied.path("error").path("data").path("rules").get(0).path("id").asText()).isEqualTo("freeze");
        verify(runs, never()).startJob(any());
    }

    @Test
    void unknownMethodsAndBrokenEnvelopes() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/list\"}");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(r.path("id").asInt()).isEqualTo(10);
        JsonNode bad = rpcResult(SampleCatalog.AGENT, "{\"id\":11,\"method\":\"ping\"}");
        assertThat(bad.path("error").path("code").asInt()).isEqualTo(-32600);
        JsonNode parse = rpcResult(SampleCatalog.AGENT, "{nope");
        assertThat(parse.path("error").path("code").asInt()).isEqualTo(-32700);
        assertThat(parse.path("id").isNull()).isTrue();
    }

    @Test
    void aJsonRpcBatchAnswersEveryRequestAndNoNotification() {
        JsonNode r = rpcResult(SampleCatalog.AGENT, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}]");
        assertThat(r.isArray()).isTrue();
        assertThat(r).hasSize(2);
        assertThat(r.get(0).path("id").asInt()).isEqualTo(1);
        assertThat(r.get(1).path("error").path("code").asInt()).isEqualTo(-32601);
        rpc(SampleCatalog.AGENT, "[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]").expectStatus().isAccepted().expectBody().isEmpty();
        JsonNode empty = rpcResult(SampleCatalog.AGENT, "[]");
        assertThat(empty.path("error").path("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void readToolsAnswerFromTheRegistryAndJobs() {
        Resource image = new Resource("image-demo01", "image", "ubuntu", "ready", JsonNodeFactory.instance.objectNode(), "team-platform", "team",
                new Resource.Origin("job_x", "build-vm-image", "1.1.0"), List.of(), Instant.parse("2026-01-01T00:00:00Z"), null);
        when(resources.list(eq("image"), any(), eq("ready"))).thenReturn(Flux.just(image));
        when(resources.get("image-demo01")).thenReturn(Mono.just(image));
        when(resources.get("missing")).thenReturn(Mono.empty());
        JsonNode list = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"list_images\",\"arguments\":{\"status\":\"ready\"}}}");
        assertThat(list.path("result").path("structuredContent").path("count").asInt()).isEqualTo(1);
        assertThat(list.path("result").path("structuredContent").path("items").get(0).path("id").asText()).isEqualTo("image-demo01");
        JsonNode get = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"get_images\",\"arguments\":{\"id\":\"image-demo01\"}}}");
        assertThat(get.path("result").path("structuredContent").path("name").asText()).isEqualTo("ubuntu");
        JsonNode missing = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_images\",\"arguments\":{\"id\":\"missing\"}}}");
        assertThat(missing.path("result").path("isError").asBoolean()).isTrue();

        when(runsQueries.job("job_1")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode().put("id", "job_1").put("status", "running")));
        when(runsQueries.job("job_9")).thenReturn(Mono.empty());
        JsonNode job = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"get_job_status\",\"arguments\":{\"jobId\":\"job_1\"}}}");
        assertThat(job.path("result").path("structuredContent").path("status").asText()).isEqualTo("running");
        JsonNode noJob = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"get_job_status\",\"arguments\":{\"jobId\":\"job_9\"}}}");
        assertThat(noJob.path("result").path("isError").asBoolean()).isTrue();

        when(runs.cancel("job_1")).thenReturn(Mono.just(CommandAccepted.of("job_1", "cancelled")));
        JsonNode cancel = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"cancel_job\",\"arguments\":{\"jobId\":\"job_1\"}}}");
        assertThat(cancel.path("result").path("structuredContent").path("status").asText()).isEqualTo("cancelled");

        when(assistant.draftWorkflow("restart a vm")).thenReturn(Mono.just(JsonNodeFactory.instance.objectNode().put("status", "ready").put("summary", "A draft")));
        JsonNode draft = rpcResult(SampleCatalog.AGENT, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"draft_workflow\",\"arguments\":{\"description\":\"restart a vm\"}}}");
        assertThat(draft.path("result").path("structuredContent").path("summary").asText()).isEqualTo("A draft");
    }

    @Test
    void getIsNotAllowedOnTheMcpEndpoint() {
        client(SampleCatalog.AGENT).get().uri("/api/v1/mcp").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().valueEquals("Allow", "POST")
                .expectBody().jsonPath("$.error").isNotEmpty();
    }
}
