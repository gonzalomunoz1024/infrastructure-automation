package com.iaf.platform.gateway.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.adapters.inbound.GeneratedRouteWebFilter;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.WorkflowInvoker;
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
import com.iaf.platform.runs.adapters.inbound.RestControllerRunsAdapter;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Idempotency keys in front of the generated gateway and the jobs endpoints. */
class IdempotencyWebFilterTest {

    static final String RESTART = "/api/v1/vms/vm-1042:restart";

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    AtomicInteger started = new AtomicInteger();
    IdempotencyWebFilter idempotency = new IdempotencyWebFilter(
            new IdempotencyHandler(new InMemoryIdempotencyStore(100), Duration.ofHours(24), Clock.systemUTC()));
    GeneratedRouteWebFilter gateway = new GeneratedRouteWebFilter(new CatalogQueryHandler(workflows, resources),
            new WorkflowInvoker(runs, policy), resources, SampleCatalog.json());

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

    WebTestClient client(Identity who) {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(identity(who), idempotency, gateway)
                .build();
    }

    WebTestClient.ResponseSpec restart(Identity who, String key, String body) {
        var request = client(who).post().uri(RESTART).contentType(MediaType.APPLICATION_JSON);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        return request.bodyValue(body).exchange();
    }

    @Test
    void theSameKeyAndBodyGetTheFirstAnswerAgainWithoutStartingAnotherJob() {
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":\"stuck\"}")
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist("Idempotent-Replay")
                .expectBody().jsonPath("$.jobId").isEqualTo("job_1");

        restart(SampleCatalog.OPS, "restart-42", "{ \"reason\" : \"stuck\" }")
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Idempotent-Replay", "true")
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.jobId").isEqualTo("job_1").jsonPath("$.links.job").isEqualTo("/api/v1/jobs/job_1");

        verify(runs, times(1)).startJob(any());
    }

    @Test
    void theSameKeyWithAnotherBodyIsAConflict() {
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":\"stuck\"}").expectStatus().isAccepted();
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":\"frozen\"}")
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody().jsonPath("$.error").value(m -> assertThat((String) m).contains("different request"));
        verify(runs, times(1)).startJob(any());
    }

    @Test
    void keysAreScopedToTheCaller() {
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":\"stuck\"}").expectBody().jsonPath("$.jobId").isEqualTo("job_1");
        restart(SampleCatalog.ADMIN, "restart-42", "{\"reason\":\"stuck\"}")
                .expectHeader().doesNotExist("Idempotent-Replay")
                .expectBody().jsonPath("$.jobId").isEqualTo("job_2");
    }

    @Test
    void aRejectedRequestFreesItsKey() {
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":42}").expectStatus().isBadRequest();
        restart(SampleCatalog.OPS, "restart-42", "{\"reason\":\"stuck\"}")
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist("Idempotent-Replay");
        verify(runs, times(1)).startJob(any());
    }

    @Test
    void withoutAKeyEveryRequestStartsAJob() {
        restart(SampleCatalog.OPS, null, "{\"reason\":\"stuck\"}").expectStatus().isAccepted();
        restart(SampleCatalog.OPS, null, "{\"reason\":\"stuck\"}").expectStatus().isAccepted();
        verify(runs, times(2)).startJob(any());
    }

    @Test
    void anInvalidKeyIsABadRequest() {
        restart(SampleCatalog.OPS, "two words", "{\"reason\":\"stuck\"}")
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").value(m -> assertThat((String) m).contains("Idempotency-Key"));
        verify(runs, times(0)).startJob(any());
    }

    @Test
    void batchesOnGeneratedRoutesTakeKeysToo() {
        for (int i = 0; i < 2; i++) {
            client(SampleCatalog.OPS).post().uri("/api/v1/vms:restart:batch").header("Idempotency-Key", "wave-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"items\":[{\"vmId\":\"vm-1\"},{\"vmId\":\"vm-2\"}]}")
                    .exchange().expectStatus().isAccepted();
        }
        verify(runs, times(1)).startBatch(any());
    }

    @Test
    void onlyJobStartingRoutesAreGuarded() {
        assertThat(IdempotencyWebFilter.applies("/api/v1/jobs")).isTrue();
        assertThat(IdempotencyWebFilter.applies("/api/v1/jobs:batch")).isTrue();
        assertThat(IdempotencyWebFilter.applies("/api/v1/jobs/")).isTrue();
        assertThat(IdempotencyWebFilter.applies(RESTART)).isTrue();
        assertThat(IdempotencyWebFilter.applies("/api/v1/jobs/job_1:cancel")).isFalse();
        assertThat(IdempotencyWebFilter.applies("/api/v1/workflows")).isFalse();
        assertThat(IdempotencyWebFilter.applies("/api/v1/mcp")).isFalse();
        assertThat(IdempotencyWebFilter.applies("/actuator/health")).isFalse();
    }

    @Test
    void thePlainJobsEndpointsReplayTheirAnswersToo() {
        RunsCommandHandler commands = mock(RunsCommandHandler.class);
        when(commands.startJob(any())).thenAnswer(inv -> Mono.fromSupplier(() -> CommandAccepted.of("job_" + started.incrementAndGet(), "queued",
                Map.of("job", "/api/v1/jobs/job_" + started.get()))));
        when(commands.startBatch(any())).thenAnswer(inv -> Mono.fromSupplier(() -> CommandAccepted.of("job_" + started.incrementAndGet(), "queued")));
        WebTestClient jobs = WebTestClient.bindToController(new RestControllerRunsAdapter(commands, mock(RunsQueryHandler.class)))
                .controllerAdvice(new GlobalExceptionHandler())
                .webFilter(identity(SampleCatalog.OPS), idempotency)
                .build();
        Map<String, Object> body = Map.of("workflow", "vm-restart", "input", Map.of("vmId", "vm-1"));

        for (int i = 0; i < 2; i++) {
            jobs.post().uri("/api/v1/jobs").header("Idempotency-Key", "job-key").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                    .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.id").isEqualTo("job_1");
        }
        jobs.post().uri("/api/v1/jobs:batch").header("Idempotency-Key", "job-key").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "items", List.of(Map.of("vmId", "vm-1"))))
                .exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        jobs.post().uri("/api/v1/jobs:batch").header("Idempotency-Key", "batch-key").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "items", List.of(Map.of("vmId", "vm-1"))))
                .exchange().expectStatus().isAccepted().expectHeader().doesNotExist("Idempotent-Replay");

        verify(commands, times(1)).startJob(any());
        verify(commands, times(1)).startBatch(any());
    }
}
