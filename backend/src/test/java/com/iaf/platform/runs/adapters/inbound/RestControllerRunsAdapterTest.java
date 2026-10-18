package com.iaf.platform.runs.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.common.domain.CommandAccepted;
import com.iaf.platform.common.domain.ConflictException;
import com.iaf.platform.common.domain.ForbiddenException;
import com.iaf.platform.common.domain.NotFoundException;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.providers.domain.ProviderDefinition;
import com.iaf.platform.runs.RunsTestWiring;
import com.iaf.platform.runs.SampleContent;
import com.iaf.platform.runs.application.RunsCommandHandler;
import com.iaf.platform.runs.application.RunsQueryHandler;
import com.iaf.platform.runs.domain.DryRunResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartBatch;
import com.iaf.platform.runs.ports.inbound.RunsCommands.StartJob;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerResult;
import com.iaf.platform.runs.ports.inbound.RunsCommands.TriggerStarted;
import com.iaf.platform.workflows.domain.WorkflowDefinition;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The HTTP surface: paths, bodies, status codes and the error mapping. */
class RestControllerRunsAdapterTest {

    private RunsCommandHandler commands;
    private RunsQueryHandler queries;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        commands = mock(RunsCommandHandler.class);
        queries = mock(RunsQueryHandler.class);
        client = WebTestClient.bindToController(new RestControllerRunsAdapter(commands, queries))
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static CommandAccepted accepted(String id, String status) {
        return CommandAccepted.of(id, status, Map.of("job", "/api/v1/jobs/" + id));
    }

    @Test
    void listsJobsWithFilters() {
        when(queries.jobs("vm-restart", "failed", null, 5)).thenReturn(Flux.just(RunsTestWiring.json("id", "job_1", "status", "failed")));

        client.get().uri("/api/v1/jobs?workflow=vm-restart&status=failed&limit=5").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].id").isEqualTo("job_1").jsonPath("$[0].status").isEqualTo("failed");
    }

    @Test
    void jobDetailAnd404() {
        when(queries.job("job_1")).thenReturn(Mono.just(RunsTestWiring.json("id", "job_1", "status", "running")));
        when(queries.job("job_x")).thenReturn(Mono.empty());

        client.get().uri("/api/v1/jobs/job_1").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("running");
        client.get().uri("/api/v1/jobs/job_x").exchange().expectStatus().isNotFound().expectBody().jsonPath("$.error").isEqualTo("job job_x not found");
    }

    @Test
    void eventsStreamAsServerSentEvents() {
        JsonNode first = SampleContent.json(Map.of("seq", 1, "jobId", "job_1", "type", "job", "data", Map.of("event", "job.started")));
        JsonNode second = SampleContent.json(Map.of("seq", 2, "jobId", "job_1", "type", "step", "data", Map.of("event", "job.step", "step", "a")));
        when(queries.events("job_1")).thenReturn(Flux.just(first, second));

        List<String> body = client.get().uri("/api/v1/jobs/job_1/events").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class).getResponseBody().collectList().block();

        String text = String.join("\n", body);
        assertThat(text).contains("\"seq\":1").contains("\"seq\":2").contains("job.step");
        List<JsonNode> typed = client.get().uri("/api/v1/jobs/job_1/events").accept(MediaType.TEXT_EVENT_STREAM).exchange()
                .returnResult(JsonNode.class).getResponseBody().collectList().block();
        assertThat(typed).hasSize(2);
        assertThat(typed.get(1).path("type").asText()).isEqualTo("step");
    }

    @Test
    void aDryRunWithDraftsReachesTheHandlerWithThem() {
        WorkflowDefinition def = SampleContent.workflow("vm-restart");
        JsonNode input = SampleContent.json(Map.of("vmId", "vm-1"));
        List<ProviderDefinition> drafts = List.of(SampleContent.provider("vsphere"));
        DryRunResult passed = new DryRunResult(true, List.of(), List.of(), null);
        when(commands.dryRun(def, input, SampleContent.GONZALO, drafts)).thenReturn(Mono.just(passed));

        assertThat(new RestControllerRunsAdapter(commands, queries).dryRun(def, input, SampleContent.GONZALO, drafts).block()).isSameAs(passed);
        verify(commands).dryRun(def, input, SampleContent.GONZALO, drafts);
    }

    @Test
    void replayPassesCompareTo() {
        when(queries.replay("job_1", "job_0")).thenReturn(Mono.just(RunsTestWiring.json("jobId", "job_1", "comparedTo", "job_0")));
        client.get().uri("/api/v1/jobs/job_1/replay?compareTo=job_0").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.comparedTo").isEqualTo("job_0");
    }

    @Test
    void startsAJobAndABatch() {
        when(commands.startJob(any())).thenReturn(Mono.just(accepted("job_9", "queued")));
        when(commands.startBatch(any())).thenReturn(Mono.just(CommandAccepted.of("job_10", "queued", Map.of("job", "/api/v1/jobs/job_10", "rows", "2"))));

        client.post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "input", Map.of("vmId", "vm-1"), "channel", "mcp"))
                .exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.id").isEqualTo("job_9").jsonPath("$.status").isEqualTo("queued").jsonPath("$.links.job").isEqualTo("/api/v1/jobs/job_9");
        ArgumentCaptor<StartJob> start = ArgumentCaptor.forClass(StartJob.class);
        verify(commands).startJob(start.capture());
        assertThat(start.getValue().workflow()).isEqualTo("vm-restart");
        assertThat(start.getValue().channel()).isEqualTo("mcp");
        assertThat(start.getValue().input().path("vmId").asText()).isEqualTo("vm-1");
        assertThat(start.getValue().requester()).isNull();

        client.post().uri("/api/v1/jobs:batch").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("workflow", "vm-restart", "items", List.of(Map.of("vmId", "a"), Map.of("vmId", "b")),
                        "rollout", Map.of("waves", List.of(50, 100), "maxParallel", 2, "stopIfFailedPercent", 10)))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.links.rows").isEqualTo("2");
        ArgumentCaptor<StartBatch> batch = ArgumentCaptor.forClass(StartBatch.class);
        verify(commands).startBatch(batch.capture());
        assertThat(batch.getValue().items()).hasSize(2);
        assertThat(batch.getValue().rollout().waves()).containsExactly(50, 100);
        assertThat(batch.getValue().rollout().maxParallel()).isEqualTo(2);
    }

    @Test
    void steeringEndpointsAcceptTheirBodies() {
        when(commands.cancel("job_1")).thenReturn(Mono.just(accepted("job_1", "cancelled")));
        when(commands.retry(eq("job_1"), eq("validate"), any())).thenReturn(Mono.just(accepted("job_1", "running")));
        when(commands.retry(eq("job_1"), isNull(), isNull())).thenReturn(Mono.just(accepted("job_1", "running")));
        when(commands.skip("job_1", "validate")).thenReturn(Mono.just(accepted("job_1", "running")));
        when(commands.abort("job_1")).thenReturn(Mono.just(accepted("job_1", "aborting")));
        when(commands.rerun(eq("job_1"), eq("restart"), any())).thenReturn(Mono.just(accepted("job_2", "queued")));
        when(commands.retryFailedRows("job_b")).thenReturn(Mono.just(accepted("job_b", "queued")));

        client.post().uri("/api/v1/jobs/job_1:cancel").exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("cancelled");
        client.post().uri("/api/v1/jobs/job_1:retry").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("step", "validate", "input", Map.of("input", Map.of("timeout", 900))))
                .exchange().expectStatus().isAccepted();
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(commands).retry(eq("job_1"), eq("validate"), input.capture());
        assertThat(input.getValue().path("input").path("timeout").asInt()).isEqualTo(900);
        client.post().uri("/api/v1/jobs/job_1:retry").exchange().expectStatus().isAccepted();
        client.post().uri("/api/v1/jobs/job_1:skip").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("step", "validate")).exchange().expectStatus().isAccepted();
        client.post().uri("/api/v1/jobs/job_1:abort").exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("aborting");
        client.post().uri("/api/v1/jobs/job_1:rerun").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("fromStep", "restart"))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.id").isEqualTo("job_2");
        client.post().uri("/api/v1/jobs/job_b:retry-failed-rows").exchange().expectStatus().isAccepted();
    }

    @Test
    void approvalsEndpoints() {
        when(queries.approvals("pending")).thenReturn(Flux.just(RunsTestWiring.json("id", "apr_1", "status", "pending")));
        when(queries.approval("apr_1")).thenReturn(Mono.just(RunsTestWiring.json("id", "apr_1")));
        when(commands.approve("apr_1", "ok")).thenReturn(Mono.just(CommandAccepted.of("apr_1", "approved", Map.of("job", "/api/v1/jobs/job_1"))));
        when(commands.reject(eq("apr_1"), isNull())).thenReturn(Mono.just(CommandAccepted.of("apr_1", "rejected", null)));

        client.get().uri("/api/v1/approvals?status=pending").exchange().expectStatus().isOk().expectBody().jsonPath("$[0].id").isEqualTo("apr_1");
        client.get().uri("/api/v1/approvals/apr_1").exchange().expectStatus().isOk();
        client.post().uri("/api/v1/approvals/apr_1:approve").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("comment", "ok"))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("approved");
        client.post().uri("/api/v1/approvals/apr_1:reject").exchange().expectStatus().isAccepted().expectBody().jsonPath("$.status").isEqualTo("rejected");
    }

    @Test
    void triggersAnswerWithWhatStarted() {
        when(commands.triggerAlert(any())).thenReturn(Mono.just(new TriggerResult(List.of(new TriggerStarted("vm-restart", "job_7")), List.of())));
        when(commands.triggerEvent(eq("vm.created"), any())).thenReturn(Mono.just(new TriggerResult(List.of(), List.of())));

        client.post().uri("/api/v1/triggers/alerts").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("source", "grafana", "labels", Map.of("alertname", "VMUnresponsive")))
                .exchange().expectStatus().isAccepted()
                .expectBody().jsonPath("$.started[0].workflow").isEqualTo("vm-restart").jsonPath("$.started[0].jobId").isEqualTo("job_7");
        client.post().uri("/api/v1/triggers/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "vm.created", "payload", Map.of("id", "vm-1")))
                .exchange().expectStatus().isAccepted().expectBody().jsonPath("$.started").isArray();
    }

    @Test
    void errorsMapToStatusCodes() {
        when(commands.cancel("job_done")).thenReturn(Mono.error(new ConflictException("Job job_done already ended (succeeded)")));
        when(commands.approve(eq("apr_2"), any())).thenReturn(Mono.error(new ForbiddenException("Ana is not in approver group compute-approvers for vm-restart")));
        when(commands.startJob(any())).thenReturn(Mono.error(new IllegalArgumentException("Invalid input for vm-restart: problems: [{\"field\":\"vmId\"}]")));
        when(commands.rerun(anyString(), any(), any())).thenReturn(Mono.error(new NotFoundException("job", "job_x")));

        client.post().uri("/api/v1/jobs/job_done:cancel").exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.error").value(v -> assertThat(v.toString()).contains("already ended"));
        client.post().uri("/api/v1/approvals/apr_2:approve").exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/jobs").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("workflow", "vm-restart", "input", Map.of()))
                .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.error").value(v -> assertThat(v.toString()).contains("problems"));
        client.post().uri("/api/v1/jobs/job_x:rerun").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("fromStep", "a")).exchange().expectStatus().isNotFound();
    }
}
