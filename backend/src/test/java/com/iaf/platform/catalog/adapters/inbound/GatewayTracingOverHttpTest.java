package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.tracing.TestTracing;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import io.micrometer.observation.Observation;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tracing on the generated routes and MCP tools/call, with server observations as Spring Boot
 * configures WebFlux: an incoming {@code traceparent} is continued, the gateway and MCP spans hang
 * under the request's, and the job they start carries the trace id and finds their span open.
 */
class GatewayTracingOverHttpTest {

    static final ObjectMapper JSON = SampleCatalog.json();
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String CALLER_SPAN = "00f067aa0ba902b7";
    static final String TRACEPARENT = "00-" + TRACE + "-" + CALLER_SPAN + "-01";

    TestTracing tracing = TestTracing.on();
    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    CatalogQueryHandler queries = new CatalogQueryHandler(workflows, resources);
    WorkflowInvoker invoker = new WorkflowInvoker(runs, policy, JobQuotas.unlimited(), workflows);
    GeneratedRouteWebFilter filter = new GeneratedRouteWebFilter(queries, invoker, resources, JSON, tracing.traces);
    McpServer mcp = new McpServer(queries, invoker, resources, mock(RunsQueries.class), runs, mock(AssistantCommands.class), JSON, null,
            tracing.traces);
    /** The span open under the job's trace id while the job was being started. */
    AtomicReference<Observation> openWhileStarting = new AtomicReference<>();

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.empty());
        when(runs.startJob(any())).thenAnswer(inv -> {
            openWhileStarting.set(tracing.traces.recall(((StartJob) inv.getArgument(0)).traceId()));
            return Mono.just(CommandAccepted.of("job_1", "queued"));
        });
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    @AfterEach
    void close() {
        tracing.close();
    }

    WebTestClient rest() {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(GeneratedRouteWebFilterTest.identity(SampleCatalog.OPS), filter)
                .apply(tracing.server())
                .build();
    }

    WebTestClient mcpClient() {
        return WebTestClient.bindToController(new RestControllerCatalogAdapter(queries, mcp)).controllerAdvice(new GlobalExceptionHandler())
                .webFilter(GeneratedRouteWebFilterTest.identity(SampleCatalog.OPS))
                .apply(tracing.server())
                .build();
    }

    StartJob started() {
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        return captor.getValue();
    }

    SpanData server() {
        return tracing.spans.stream().filter(s -> s.getKind() == SpanKind.SERVER).findFirst()
                .orElseThrow(() -> new AssertionError("no server span among " + tracing.spans));
    }

    @Test
    void anIncomingTraceparentIsContinuedAndTheJobCarriesItsTraceId() {
        rest().post().uri("/api/v1/vms/vm-1042:restart").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"stuck\"}").exchange().expectStatus().isAccepted();

        assertThat(started().traceId()).isEqualTo(TRACE);
        SpanData server = server();
        SpanData gateway = tracing.one("gateway invoke vm-restart");
        assertThat(server.getTraceId()).isEqualTo(TRACE);
        assertThat(server.getParentSpanId()).isEqualTo(CALLER_SPAN);
        assertThat(gateway.getTraceId()).isEqualTo(TRACE);
        assertThat(gateway.getParentSpanId()).isEqualTo(server.getSpanId());
        assertThat(gateway.getAttributes().get(AttributeKey.stringKey("operation"))).isEqualTo("invoke");
        assertThat(gateway.getAttributes().get(AttributeKey.stringKey("target"))).isEqualTo("vm-restart");
        assertThat(gateway.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("202");
        assertThat(tracing.traces.spanId(openWhileStarting.get())).isEqualTo(gateway.getSpanId());
    }

    @Test
    void withoutATraceparentTheRequestStartsTheTraceTheJobCarries() {
        rest().post().uri("/api/v1/vms/vm-1042:restart").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"stuck\"}").exchange().expectStatus().isAccepted();

        SpanData server = server();
        assertThat(server.getParentSpanContext().isValid()).isFalse();
        assertThat(started().traceId()).isEqualTo(server.getTraceId());
        assertThat(tracing.one("gateway invoke vm-restart").getParentSpanId()).isEqualTo(server.getSpanId());
    }

    @Test
    void aBatchRunsInTheRequestsTrace() {
        rest().post().uri("/api/v1/vms:restart:batch").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"vmId\":\"vm-1\",\"reason\":\"a\"},{\"vmId\":\"vm-2\",\"reason\":\"b\"}]}")
                .exchange().expectStatus().isAccepted();

        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        assertThat(captor.getValue().traceId()).isEqualTo(TRACE);
        assertThat(tracing.one("gateway batch vm-restart").getTraceId()).isEqualTo(TRACE);
    }

    @Test
    void aRefusedRequestIsAnAnswerNotAFailedSpan() {
        rest().post().uri("/api/v1/vms/vm-1042:restart").header("X-Workflow-Version", "the latest").exchange().expectStatus().isBadRequest();

        SpanData gateway = tracing.one("gateway invoke vm-restart");
        assertThat(gateway.getAttributes().get(AttributeKey.stringKey("status"))).isEqualTo("400");
        assertThat(gateway.getStatus().getStatusCode()).isNotEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    void anMcpToolCallIsASpanInTheCallersTraceAndTheJobCarriesIt() {
        mcpClient().post().uri("/api/v1/mcp").header("traceparent", TRACEPARENT).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\","
                        + "\"arguments\":{\"vmId\":\"vm-1\",\"reason\":\"stuck\"}}}")
                .exchange().expectStatus().isOk();

        assertThat(started().traceId()).isEqualTo(TRACE);
        SpanData call = tracing.one("mcp tools/call");
        assertThat(call.getTraceId()).isEqualTo(TRACE);
        assertThat(call.getParentSpanId()).isEqualTo(server().getSpanId());
        assertThat(call.getAttributes().get(AttributeKey.stringKey("tool"))).isEqualTo("vm_restart");
        assertThat(tracing.traces.spanId(openWhileStarting.get())).isEqualTo(call.getSpanId());
    }
}
