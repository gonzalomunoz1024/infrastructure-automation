package com.iaf.platform.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.adapters.inbound.GeneratedRouteWebFilter;
import com.iaf.platform.catalog.adapters.inbound.RestControllerCatalogAdapter;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.McpServer;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.gateway.application.GatewayProperties;
import com.iaf.platform.gateway.application.JobQuotas;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.adapters.inbound.RestControllerRunsAdapter;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsQueries;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Team quotas on every way in that starts jobs: generated routes, MCP and the jobs endpoints. */
class JobQuotasOverHttpTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    JobQuotas quotas = new JobQuotas(twoAtOnce(), CLOCK);
    WorkflowInvoker invoker = new WorkflowInvoker(runs, policy, quotas);
    CatalogQueryHandler catalog = new CatalogQueryHandler(workflows, resources);

    static GatewayProperties.Quotas twoAtOnce() {
        GatewayProperties.Quotas q = new GatewayProperties.Quotas();
        q.setPerMinute(30);
        q.setBurst(2);
        return q;
    }

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.empty());
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    static WebFilter identity(Identity who) {
        return (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
        };
    }

    WebTestClient gateway(Identity who) {
        GeneratedRouteWebFilter filter = new GeneratedRouteWebFilter(catalog, invoker, resources, SampleCatalog.json());
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(identity(who), filter)
                .build();
    }

    @Test
    void aGeneratedRouteOverTheTeamQuotaIsA429WithRetryAfter() {
        gateway(SampleCatalog.OPS).post().uri("/api/v1/vms/vm-1:restart").exchange().expectStatus().isAccepted();
        gateway(SampleCatalog.AGENT).post().uri("/api/v1/vms/vm-2:restart").exchange().expectStatus().isAccepted();
        gateway(SampleCatalog.OPS).post().uri("/api/v1/vms/vm-3:restart").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectBody()
                .jsonPath("$.error").isEqualTo("Team team-compute has used its quota of 30 job starts a minute (2 at once); try again in 2 s")
                .jsonPath("$.retryAfter").isEqualTo(2);
        verify(runs, times(2)).startJob(any());

        gateway(SampleCatalog.DEVELOPER).post().uri("/api/v1/vms/vm-3:restart").exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(429));
    }

    @Test
    void aBatchTakesOneTokenWhateverItsSize() {
        String rows = "{\"items\":[{\"vmId\":\"vm-1\"},{\"vmId\":\"vm-2\"},{\"vmId\":\"vm-3\"},{\"vmId\":\"vm-4\"}]}";
        for (int i = 0; i < 2; i++) {
            gateway(SampleCatalog.OPS).post().uri("/api/v1/vms:restart:batch").contentType(MediaType.APPLICATION_JSON).bodyValue(rows)
                    .exchange().expectStatus().isAccepted();
        }
        gateway(SampleCatalog.OPS).post().uri("/api/v1/vms:restart:batch").contentType(MediaType.APPLICATION_JSON).bodyValue(rows)
                .exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(runs, times(2)).startBatch(any());
    }

    @Test
    void theJobsEndpointsAnswer429Too() {
        RunsCommandHandler commands = mock(RunsCommandHandler.class);
        when(commands.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(commands.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_2", "queued")));
        WebTestClient jobs = WebTestClient.bindToController(new RestControllerRunsAdapter(commands, mock(RunsQueryHandler.class), quotas))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(identity(SampleCatalog.OPS))
                .build();

        jobs.post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of("vmId", "vm-1"))).exchange().expectStatus().isAccepted();
        jobs.post().uri("/api/v1/jobs:batch").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "items", List.of(Map.of("vmId", "vm-1")))).exchange().expectStatus().isAccepted();
        jobs.post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of("vmId", "vm-1"))).exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().valueEquals("Retry-After", "2")
                .expectBody().jsonPath("$.error").value(m -> assertThat((String) m).contains("team-compute"));
        verify(commands, times(1)).startJob(any());
    }

    @Test
    void anMcpCallOverTheQuotaIsAnErrorThatSaysWhenToRetry() {
        McpServer mcp = new McpServer(catalog, invoker, resources, mock(RunsQueries.class), runs, mock(AssistantCommands.class), SampleCatalog.json());
        WebTestClient client = WebTestClient.bindToController(new RestControllerCatalogAdapter(catalog, mcp))
                .controllerAdvice(new GlobalExceptionHandler()).webFilter(identity(SampleCatalog.AGENT)).build();
        String call = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"vm_restart\",\"arguments\":{\"vmId\":\"vm-1\"}}}";

        JsonNode last = null;
        for (int i = 0; i < 3; i++) {
            last = client.post().uri("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON).bodyValue(call).exchange()
                    .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        }
        assertThat(last.path("error").path("message").asText()).contains("quota");
        assertThat(last.path("error").path("data").path("tooManyRequests").asBoolean()).isTrue();
        assertThat(last.path("error").path("data").path("retryAfter").asLong()).isEqualTo(Duration.ofSeconds(2).toSeconds());
        verify(runs, times(2)).startJob(any());
    }
}
