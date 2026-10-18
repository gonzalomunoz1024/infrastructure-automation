package com.iaf.platform.catalog.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.WebFilter;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.platform.catalog.SampleCatalog;
import com.iaf.platform.catalog.application.CatalogQueryHandler;
import com.iaf.platform.catalog.application.WorkflowInvoker;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort;
import com.iaf.platform.common.ports.PolicyPort.PolicyDecision;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.identity.adapters.inbound.IdentityWebFilter;
import com.iaf.platform.identity.application.CurrentIdentity;
import com.iaf.platform.identity.application.ServiceScopes;
import com.iaf.platform.resources.domain.Resource;
import com.iaf.platform.resources.ports.inbound.ResourceQueries;
import com.iaf.platform.runs.domain.Preview;
import com.iaf.platform.runs.ports.inbound.RunsCommands;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.workflows.domain.WorkflowRecord;
import com.iaf.platform.workflows.domain.WorkflowStatus;
import com.iaf.platform.workflows.domain.WorkflowYaml;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The gateway over the sample content, with the runs and policy ports mocked. */
class GeneratedRouteWebFilterTest {

    WorkflowQueries workflows = mock(WorkflowQueries.class);
    ResourceQueries resources = mock(ResourceQueries.class);
    RunsCommands runs = mock(RunsCommands.class);
    PolicyPort policy = mock(PolicyPort.class);
    GeneratedRouteWebFilter filter = new GeneratedRouteWebFilter(new CatalogQueryHandler(workflows, resources),
            new WorkflowInvoker(runs, policy), resources, SampleCatalog.json());

    @BeforeEach
    void content() {
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(SampleCatalog.workflows()));
        when(resources.types()).thenReturn(Flux.fromIterable(SampleCatalog.types()));
        when(policy.engine()).thenReturn("mock");
        when(policy.evaluate(any())).thenReturn(Mono.just(PolicyDecision.allow("mock")));
        when(runs.preview(any(), any(), any())).thenAnswer(inv -> Mono.just(new Preview(
                ((com.iaf.platform.workflows.domain.WorkflowDefinition) inv.getArgument(0)).name(), "1.0.0",
                List.of(new Preview.PreviewStep("restart", "Restart", com.iaf.platform.common.domain.Effect.CHANGE, "power cycle")),
                List.of(), null, PolicyDecision.allow("mock"))));
        when(runs.startJob(any())).thenReturn(Mono.just(CommandAccepted.of("job_1", "queued")));
        when(runs.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_b", "queued")));
    }

    /** What the identity filter does, for one identity. */
    static WebFilter identity(Identity who) {
        return (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            return chain.filter(exchange).contextWrite(ctx -> CurrentIdentity.with(ctx, who));
        };
    }

    WebTestClient client(Identity who) {
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    exchange.getResponse().getHeaders().add("X-Fell-Through", "yes");
                    return exchange.getResponse().setComplete();
                })
                .webFilter(identity(who), filter)
                .build();
    }

    /** What the identity filter does for a service account: its identity and its scopes. */
    WebTestClient account(Identity who, List<String> scopes) {
        WebFilter identity = (exchange, chain) -> {
            exchange.getAttributes().put(IdentityWebFilter.ATTRIBUTE, who);
            exchange.getAttributes().put(ServiceScopes.ATTRIBUTE, scopes);
            return chain.filter(exchange).contextWrite(ctx -> ServiceScopes.with(CurrentIdentity.with(ctx, who), scopes));
        };
        return WebTestClient.bindToWebHandler(exchange -> {
                    exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
                    return exchange.getResponse().setComplete();
                })
                .webFilter(identity, filter)
                .build();
    }

    @Test
    void aServiceAccountStartsJobsWithJobsStartAndReadsResourcesWithRegistryRead() {
        when(resources.list(eq("image"), any(), any())).thenReturn(Flux.empty());
        WebTestClient reader = account(SampleCatalog.AGENT, List.of("registry:read"));
        reader.post().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").value(org.hamcrest.Matchers.containsString("jobs:start"));
        reader.post().uri("/api/v1/vms/vm-1:restart:preview").exchange().expectStatus().isForbidden();
        reader.get().uri("/api/v1/images").exchange().expectStatus().isOk();
        verify(runs, never()).startJob(any());

        WebTestClient starter = account(SampleCatalog.AGENT, List.of("jobs:start"));
        starter.post().uri("/api/v1/vms/vm-1:restart").exchange().expectStatus().isAccepted();
        starter.get().uri("/api/v1/images").exchange().expectStatus().isForbidden();
    }

    @Test
    void startsAJobForAGeneratedRoute() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms/vm-1042:restart")
                .header("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"reason\":\"stuck\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.jobId").isEqualTo("job_1")
                .jsonPath("$.status").isEqualTo("queued")
                .jsonPath("$.links.job").isEqualTo("/api/v1/jobs/job_1");
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        StartJob job = captor.getValue();
        assertThat(job.workflow()).isEqualTo("vm-restart");
        assertThat(job.input().path("vmId").asText()).isEqualTo("vm-1042");
        assertThat(job.input().path("reason").asText()).isEqualTo("stuck");
        assertThat(job.channel()).isEqualTo("rest");
        assertThat(job.requester().id()).isEqualTo("mateo");
        assertThat(job.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(job.preview()).isNotNull();
        assertThat(job.version()).isNull();
    }

    @Test
    void anEmptyBodyIsFineWhenThePathCarriesTheInput() {
        client(SampleCatalog.OPS).post().uri(java.net.URI.create("/api/v1/vms/vm%207:restart")).exchange().expectStatus().isAccepted();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        assertThat(captor.getValue().input().path("vmId").asText()).isEqualTo("vm 7");
        assertThat(captor.getValue().traceId()).startsWith("trace_");
    }

    @Test
    void corsPreflightsAreLeftToTheFramework() {
        client(SampleCatalog.OPS).options().uri("/api/v1/vms").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST").exchange()
                .expectStatus().isNotFound().expectHeader().exists("X-Fell-Through");
    }

    @Test
    void rejectsAnInputThatFailsTheSchemaWithProblems() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"web-1\",\"size\":\"huge\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isNotEmpty()
                .jsonPath("$.problems").isArray()
                .jsonPath("$.problems[0].level").isEqualTo("error")
                .jsonPath("$.problems[0].code").isEqualTo("schema");
        verify(runs, never()).startJob(any());
    }

    @Test
    void rejectsMalformedJson() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms").contentType(MediaType.APPLICATION_JSON).bodyValue("{not json")
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").value(s -> assertThat((String) s).contains("Malformed JSON"));
    }

    @Test
    void forbidsACallerWithoutARoleOfThePolicy() {
        client(SampleCatalog.GUEST).post().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").value(s -> assertThat((String) s).contains("may not invoke vm-restart"));
        verify(runs, never()).startJob(any());
        verify(policy, never()).evaluate(any());
    }

    @Test
    void forbidsWhenPolicyDeniesAndTellsWhich() {
        when(policy.evaluate(any())).thenReturn(Mono.just(new PolicyDecision("deny", List.of(new Rule("change-window", "deny", "Outside the change window")), "mock")));
        client(SampleCatalog.OPS).post().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.error").value(s -> assertThat((String) s).contains("Outside the change window"))
                .jsonPath("$.rules[0].id").isEqualTo("change-window")
                .jsonPath("$.engine").isEqualTo("mock");
        verify(runs, never()).startJob(any());
    }

    @Test
    void aBatchListsEveryInvalidRowBeforeAnythingStarts() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms:batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"name\":\"web-1\"},{\"name\":\"BAD\"},{\"size\":\"huge\"}],\"defaults\":{\"image\":\"image-demo01\",\"size\":\"small\"}}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isNotEmpty()
                .jsonPath("$.rows.length()").isEqualTo(2)
                .jsonPath("$.rows[0].index").isEqualTo(1)
                .jsonPath("$.rows[0].problems").isArray()
                .jsonPath("$.rows[1].index").isEqualTo(2);
        verify(runs, never()).startBatch(any());
    }

    @Test
    void aValidBatchStartsOnceAndAnswersTheRowCount() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms:patch:batch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"items\":[{\"vmId\":\"vm-1\"},{\"vmId\":\"vm-2\"},{\"vmId\":\"vm-3\"}],\"defaults\":{\"cve\":\"CVE-2026-1234\"}}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.jobId").isEqualTo("job_b")
                .jsonPath("$.rows").isEqualTo(3)
                .jsonPath("$.links.job").isEqualTo("/api/v1/jobs/job_b");
        ArgumentCaptor<StartBatch> captor = ArgumentCaptor.forClass(StartBatch.class);
        verify(runs).startBatch(captor.capture());
        assertThat(captor.getValue().items()).hasSize(3);
        // the batch lives on the collection, so every row keeps its own vmId
        assertThat(captor.getValue().items()).extracting(i -> i.path("vmId").asText()).containsExactly("vm-1", "vm-2", "vm-3");
        assertThat(captor.getValue().items()).allMatch(i -> i.path("cve").asText().equals("CVE-2026-1234"));
        verify(policy).evaluate(any());
        verify(runs).preview(any(), any(), any());
    }

    @Test
    void previewAnswersTheBlastRadiusWithoutStarting() {
        client(SampleCatalog.OPS).post().uri("/api/v1/vms/vm-1:restart:preview").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.workflow").isEqualTo("vm-restart")
                .jsonPath("$.steps[0].effect").isEqualTo("change")
                .jsonPath("$.policy.decision").isEqualTo("allow");
        verify(runs, never()).startJob(any());
    }

    @Test
    void readsTheResourcesOfAType() {
        Resource image = new Resource("image-demo01", "image", "ubuntu", "ready", JsonNodeFactory.instance.objectNode().put("os", "ubuntu-24.04"),
                "team-platform", "team", new Resource.Origin("job_x", "build-vm-image", "1.1.0"), List.of(), Instant.parse("2026-01-01T00:00:00Z"), null);
        when(resources.list(eq("image"), any(), eq("ready"))).thenReturn(Flux.just(image));
        when(resources.get("image-demo01")).thenReturn(Mono.just(image));
        when(resources.get("nope")).thenReturn(Mono.empty());
        client(SampleCatalog.GUEST).get().uri("/api/v1/images?status=ready").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo("image-demo01")
                .jsonPath("$[0].createdAt").isEqualTo("2026-01-01T00:00:00Z")
                .jsonPath("$[0].attributes.os").isEqualTo("ubuntu-24.04");
        client(SampleCatalog.GUEST).get().uri("/api/v1/images/image-demo01").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.type").isEqualTo("image");
        client(SampleCatalog.GUEST).get().uri("/api/v1/images/nope").exchange()
                .expectStatus().isNotFound().expectBody().jsonPath("$.error").isNotEmpty();
        // a vm id is not an image
        when(resources.get("vm-demo01")).thenReturn(Mono.just(image.withStatus("ready", null)));
        client(SampleCatalog.GUEST).get().uri("/api/v1/vms/image-demo01").exchange().expectStatus().isNotFound();
    }

    @Test
    void platformRoutesAndUnknownPathsFallThrough() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: job-restart, title: Job restart, noun: job, verb: restart, owner: team-compute, version: 1.0.0 }
                api: { method: POST, path: "/api/v1/jobs/{id}:restart" }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """;
        List<WorkflowRecord> all = new ArrayList<>(SampleCatalog.workflows());
        all.add(WorkflowRecord.of(WorkflowYaml.parse(yaml), WorkflowStatus.PUBLISHED, "x", Instant.EPOCH, "t"));
        when(workflows.publishedAll()).thenReturn(Flux.fromIterable(all));
        client(SampleCatalog.ADMIN).post().uri("/api/v1/jobs/job_1:restart").exchange()
                .expectStatus().isNotFound().expectHeader().exists("X-Fell-Through");
        client(SampleCatalog.ADMIN).get().uri("/api/v1/jobs").exchange()
                .expectStatus().isNotFound().expectHeader().exists("X-Fell-Through");
        client(SampleCatalog.ADMIN).get().uri("/api/v1/nothing-here").exchange()
                .expectStatus().isNotFound().expectHeader().exists("X-Fell-Through");
        client(SampleCatalog.ADMIN).get().uri("/actuator/health").exchange()
                .expectStatus().isNotFound().expectHeader().exists("X-Fell-Through");
        verify(runs, never()).startJob(any());
    }

    @Test
    void theMethodMustMatch() {
        client(SampleCatalog.ADMIN).get().uri("/api/v1/vms/vm-1:restart").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().valueEquals("Allow", "POST")
                .expectBody().jsonPath("$.error").isNotEmpty();
        client(SampleCatalog.ADMIN).put().uri("/api/v1/vms").exchange()
                .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().valueEquals("Allow", "POST, GET");
        verify(runs, never()).startJob(any());
    }

    @Test
    void aReadWorkflowTakesItsInputFromTheQueryString() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata: { name: cluster-info, title: Cluster info, noun: cluster, verb: read, owner: team-data, version: 1.0.0 }
                api:
                  method: GET
                  path: /api/v1/clusters/{clusterId}
                  input:
                    type: object
                    required: [clusterId]
                    properties:
                      clusterId: { type: string }
                      limit: { type: integer }
                      verbose: { type: boolean }
                workflow:
                  do:
                    - noop:
                        set: { x: 1 }
                """;
        when(workflows.publishedAll()).thenReturn(Flux.just(WorkflowRecord.of(WorkflowYaml.parse(yaml), WorkflowStatus.PUBLISHED, "x", Instant.EPOCH, "t")));
        client(SampleCatalog.ADMIN).get().uri("/api/v1/clusters/c-1?limit=5&verbose=true").exchange().expectStatus().isAccepted();
        ArgumentCaptor<StartJob> captor = ArgumentCaptor.forClass(StartJob.class);
        verify(runs).startJob(captor.capture());
        assertThat(captor.getValue().input().path("clusterId").asText()).isEqualTo("c-1");
        assertThat(captor.getValue().input().path("limit").asInt()).isEqualTo(5);
        assertThat(captor.getValue().input().path("verbose").asBoolean()).isTrue();
    }
}
