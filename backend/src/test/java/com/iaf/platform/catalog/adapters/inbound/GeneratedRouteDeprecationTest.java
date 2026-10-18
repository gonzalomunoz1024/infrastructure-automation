package com.iaf.platform.catalog.adapters.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.catalog.domain.ApiDeprecationsTest;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Effect;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A deprecated workflow under {@code /api/v2/}: it still runs, and every answer says it is going away. */
class GeneratedRouteDeprecationTest {

    static final String SINCE = "@" + Instant.parse("2026-11-01T00:00:00Z").getEpochSecond();

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    GeneratedRouteWebFilter filter = new GeneratedRouteWebFilter(new CatalogQueryHandler(workflows, resources),
            new WorkflowInvoker(runs, policy), resources, SampleCatalog.json());

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.just(ApiDeprecationsTest.deprecatedRestart()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenReturn(Mono.just(new Preview("vm-restart", "1.2.0",
                List.of(new Preview.PreviewStep("restart", "Restart", Effect.CHANGE, "power cycle")),
                List.of(), null, PolicyDecision.allow("mock"))));
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    WebTestClient client() {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(GeneratedRouteWebFilterTest.identity(SampleCatalog.OPS), filter)
                .build();
    }

    @Test
    void aDeprecatedOperationStillRunsAndSaysWhenItGoesAway() {
        client().post().uri("/api/v2/machines/vm-1042:restart").exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Deprecation", SINCE)
                .expectHeader().valueEquals("Sunset", "Sat, 01 May 2027 00:00:00 GMT")
                .expectHeader().valueEquals("Link", "</api/v3/machines/vm-1042:reboot>; rel=\"successor-version\"")
                .expectBody().jsonPath("$.jobId").isEqualTo("job_1");
    }

    @Test
    void itsBatchPreviewAndErrorAnswersCarryTheHeadersToo() {
        client().post().uri("/api/v2/machines:restart:batch").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"vmId\":\"vm-1\"}]}")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Deprecation", SINCE)
                .expectHeader().valueEquals("Link", "</api/v3/machines/{vmId}:reboot>; rel=\"successor-version\"");
        client().post().uri("/api/v2/machines/vm-1:restart:preview").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Deprecation", SINCE);
        client().post().uri("/api/v2/machines/vm-1:restart").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"reason\":42}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().valueEquals("Deprecation", SINCE);
    }

    @Test
    void anOperationThatIsNotDeprecatedCarriesNoneOfThem() {
        when(workflows.publishedAll()).thenReturn(Flux.just(SampleCatalog.workflow("vm-restart")));
        client().post().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist("Deprecation")
                .expectHeader().doesNotExist("Sunset")
                .expectHeader().doesNotExist("Link");
    }

    @Test
    void theOldPathIsGoneOnceTheWorkflowMoved() {
        client().post().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isNotFound()
                .expectHeader().doesNotExist("Deprecation");
    }
}
