package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.catalog.application.WorkflowInvokerPinningTest;
import com.iaf.platform.catalog.domain.McpTools;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.adapters.outbound.InMemoryIdempotencyStore;
import com.iaf.platform.gateway.application.IdempotencyHandler;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** X-Workflow-Version and ?version= on the generated routes, and {@code version} on MCP tools/call. */
class VersionPinningOverHttpTest {

    static final ObjectMapper JSON = SampleCatalog.json();

    /** A read whose own input has a version field, and one whose input has none. */
    static final String REPORT = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: report-read, title: Report, noun: report, verb: read, owner: team-compute, version: 1.0.0 }
            api:
              method: GET
              path: /api/v1/reports/{id}
              input:
                type: object
                required: [id]
                properties:
                  id: { type: string }
                  version: { type: string }
            mcp: { expose: true, description: Read a report at one of its versions }
            workflow:
              do:
                - noop:
                    set: { x: 1 }
            """;
    static final String CLUSTER = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: cluster-read, title: Cluster, noun: cluster, verb: read, owner: team-compute, version: 1.0.0 }
            api:
              method: GET
              path: /api/v1/clusters/{clusterId}
              input:
                type: object
                required: [clusterId]
                properties:
                  clusterId: { type: string }
                  verbose: { type: boolean }
            workflow:
              do:
                - noop:
                    set: { x: 1 }
            """;

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    CatalogQueryHandler queries = new CatalogQueryHandler(workflows, resources);
    WorkflowInvoker invoker = new WorkflowInvoker(runs, policy, JobQuotas.unlimited(), workflows);
    GeneratedRouteWebFilter filter = new GeneratedRouteWebFilter(queries, invoker, resources, JSON);
    McpServer mcp = new McpServer(queries, invoker, resources, mock(RunsQueries.class), runs, mock(AssistantCommands.class), JSON,
            new IdempotencyHandler(new InMemoryIdempotencyStore(100), Duration.ofHours(24), Clock.systemUTC()));

    static WorkflowRecord record(String yaml) {
        return WorkflowRecord.of(WorkflowYaml.parse(yaml), WorkflowStatus.PUBLISHED, "x.yaml", Instant.EPOCH, "test");
    }

    @BeforeEach
    void content() {
        List<WorkflowRecord> all = new ArrayList<>(SampleCatalog.workflows());
        all.add(record(REPORT));
        all.add(record(CLUSTER));
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(all));
        when(workflows.version("vm-restart", "1.1.0")).thenReturn(Mono.just(WorkflowInvokerPinningTest.restart("1.1.0", WorkflowStatus.RETIRED, true)));
        when(workflows.version("vm-restart", "1.3.0")).thenReturn(Mono.just(WorkflowInvokerPinningTest.restart("1.3.0", WorkflowStatus.DRAFT, false)));
        when(workflows.version("vm-restart", "9.9.9")).thenReturn(Mono.empty());
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.empty());
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    WebTestClient rest() {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(GeneratedRouteWebFilterTest.identity(SampleCatalog.OPS), filter)
                .build();
    }

    JsonNode mcpCall(String tool, String arguments) {
        byte[] body = WebTestClient.bindToController(new RestControllerCatalogAdapter(queries, mcp)).controllerAdvice(new GlobalExceptionHandler())
                .webFilter(GeneratedRouteWebFilterTest.identity(SampleCatalog.OPS)).build()
                .post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}")
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            return JSON.readTree(body);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    StartJob started() {
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        return captor.getValue();
    }

    StartBatch batchStarted() {
        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        return captor.getValue();
    }

    // ---------------------------------------------------------------- REST

    @Test
    void theHeaderPinsAGeneratedOperationToThatVersion() {
        rest().post().uri("/api/v1/vms/vm-1:restart").header("X-Workflow-Version", "1.1.0")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":\"stuck\"}")
                .exchange().expectStatus().isAccepted();
        assertThat(started().version()).isEqualTo("1.1.0");
    }

    @Test
    void theQueryParameterPinsTooAndBothMustAgree() {
        rest().post().uri("/api/v1/vms/vm-1:restart?version=1.2.0").exchange().expectStatus().isAccepted();
        assertThat(started().version()).isEqualTo("1.2.0");
        assertThat(started().input().has("version")).isFalse();

        rest().post().uri("/api/v1/vms/vm-1:restart?version=1.1.0").header("X-Workflow-Version", "1.2.0").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("X-Workflow-Version 1.2.0 and ?version=1.1.0 name different versions");
    }

    @Test
    void batchesAndPreviewsArePinnedTheSameWay() {
        rest().post().uri("/api/v1/vms:restart:batch").header("X-Workflow-Version", "1.1.0").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"vmId\":\"vm-1\",\"reason\":\"a\"},{\"vmId\":\"vm-2\",\"reason\":\"b\"}]}")
                .exchange().expectStatus().isAccepted();
        assertThat(batchStarted().version()).isEqualTo("1.1.0");

        // 1.1.0 wanted a reason; the published 1.2.0 does not
        rest().post().uri("/api/v1/vms/vm-1:restart:preview").header("X-Workflow-Version", "1.1.0").exchange().expectStatus().isBadRequest();
        rest().post().uri("/api/v1/vms/vm-1:restart:preview").exchange().expectStatus().isOk();
    }

    @Test
    void aVersionThatCannotRunIsRefusedAndNothingStarts() {
        rest().post().uri("/api/v1/vms/vm-1:restart").header("X-Workflow-Version", "1.3.0").exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.error").isEqualTo("vm-restart@1.3.0 is draft; only published or retired versions can be pinned");
        rest().post().uri("/api/v1/vms/vm-1:restart").header("X-Workflow-Version", "9.9.9").exchange().expectStatus().isNotFound();
        rest().post().uri("/api/v1/vms/vm-1:restart").header("X-Workflow-Version", "the latest").exchange().expectStatus().isBadRequest();
        verify(runs, never()).startJob(any());
    }

    @Test
    void onAReadTheQueryParameterIsInputWhenTheWorkflowDeclaresIt() {
        rest().get().uri("/api/v1/reports/r-1?version=7").exchange().expectStatus().isAccepted();
        StartJob report = started();
        assertThat(report.version()).isNull();
        assertThat(report.input().path("version").asText()).isEqualTo("7");

        rest().get().uri("/api/v1/clusters/c-1?version=1.0.0&verbose=true").exchange().expectStatus().isAccepted();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs, org.mockito.Mockito.times(2)).startJob(captor.capture());
        StartJob cluster = captor.getAllValues().get(1);
        assertThat(cluster.version()).isEqualTo("1.0.0");
        assertThat(cluster.input().has("version")).isFalse();
        assertThat(cluster.input().path("verbose").asBoolean()).isTrue();
    }

    // ---------------------------------------------------------------- MCP

    @Test
    void mcpToolsCallTakesAVersionArgument() {
        JsonNode result = mcpCall("vm_restart", "{\"vmId\":\"vm-1\",\"reason\":\"stuck\",\"version\":\"1.1.0\"}").path("result");
        assertThat(result.path("structuredContent").path("jobId").asText()).isEqualTo("job_1");
        StartJob job = started();
        assertThat(job.version()).isEqualTo("1.1.0");
        assertThat(job.input().has("version")).isFalse();

        mcpCall("vm_restart_batch", "{\"items\":[{\"vmId\":\"vm-1\",\"reason\":\"a\"}],\"version\":\"1.1.0\"}");
        assertThat(batchStarted().version()).isEqualTo("1.1.0");

        JsonNode error = mcpCall("vm_restart", "{\"vmId\":\"vm-1\",\"version\":\"1.3.0\"}").path("error");
        assertThat(error.path("data").path("conflict").asBoolean()).isTrue();
    }

    @Test
    void aWorkflowsOwnVersionFieldStaysItsInputOnMcp() {
        mcpCall("report_read", "{\"id\":\"r-1\",\"version\":\"7\"}");
        StartJob job = started();
        assertThat(job.version()).isNull();
        assertThat(job.input().path("version").asText()).isEqualTo("7");

        List<McpTools.Tool> tools = McpTools.of(SampleCatalog.workflows(), SampleCatalog.types());
        McpTools.Tool restart = McpTools.find(tools, "vm_restart").orElseThrow();
        assertThat(restart.inputSchema().path("properties").path("version").path("type").asText()).isEqualTo("string");
        assertThat(restart.inputSchema().path("properties").path("version").path("description").asText()).contains("release router");
        assertThat(McpTools.find(tools, "vm_restart_batch").orElseThrow().inputSchema().path("properties").has("version")).isTrue();
    }

    @Test
    void anIdempotencyKeyRemembersThePinnedVersion() {
        mcpCall("vm_restart", "{\"vmId\":\"vm-1\",\"reason\":\"a\",\"version\":\"1.1.0\",\"idempotencyKey\":\"k-1\"}");
        JsonNode error = mcpCall("vm_restart", "{\"vmId\":\"vm-1\",\"reason\":\"a\",\"version\":\"1.2.0\",\"idempotencyKey\":\"k-1\"}").path("error");
        assertThat(error.path("data").path("conflict").asBoolean()).isTrue();
        assertThat(started().version()).isEqualTo("1.1.0");
    }
}
