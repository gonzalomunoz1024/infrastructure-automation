package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.Banner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.PlatformApplication;
import com.iaf.platform.audit.ports.outbound.EvidenceStore;
import com.iaf.platform.common.adapters.telemetry.JdbcTelemetry;
import com.iaf.platform.common.ports.TelemetryPort;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

/**
 * The whole application with the {@code postgres} profile, on an H2 file database in PostgreSQL
 * mode: a workflow published through a change request, the job that ran it and the job's
 * evidence are still there after the context is closed and started again, and none of it needed
 * a statement on a non-blocking thread.
 */
class PostgresProfileRestartTest {

    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: thing-refresh, title: Refresh a thing, noun: thing, verb: refresh, owner: team-compute }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hi {{ $job.requester }}" }
            """;

    /**
     * Arguments, not default properties: the postgres profile's own datasource settings in
     * application.yml would win over those. The fixed signing key is a test value, so a seal made
     * before the restart still verifies after it.
     */
    static ConfigurableApplicationContext start(Path dir) {
        return start(dir, false);
    }

    static ConfigurableApplicationContext start(Path dir, boolean demoHistory) {
        return new SpringApplicationBuilder(PlatformApplication.class)
                .profiles("demo", "postgres")
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run("--server.port=0",
                        "--spring.datasource.url=jdbc:h2:file:" + dir.resolve("platform") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                        "--spring.datasource.username=sa",
                        "--spring.datasource.password=",
                        "--platform.content.dir=..",
                        "--platform.demo.seed-history=" + demoHistory,
                        "--platform.audit.signing-key=test-signing-key");
    }

    /** Rows in the tables the demo history fills, once the telemetry queue is written. */
    static Map<String, Long> rows(ConfigurableApplicationContext ctx) {
        ctx.getBean(JdbcTelemetry.class).flush();
        Jdbc jdbc = ctx.getBean(Jdbc.class);
        Map<String, Long> rows = new TreeMap<>();
        for (String table : List.of("workflow_versions", "change_requests", "releases", "resources", "runs_jobs", "runs_events",
                "evidence_records", "evidence_entries", "telemetry_samples")) {
            rows.put(table, jdbc.first("SELECT COUNT(*) FROM " + table, (rs, i) -> rs.getLong(1)).orElse(0L));
        }
        return rows;
    }

    static Set<String> ids(JsonNode list) {
        Set<String> ids = new TreeSet<>();
        list.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    static WebTestClient http(ConfigurableApplicationContext ctx) {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + ctx.getEnvironment().getProperty("local.server.port"))
                .responseTimeout(Duration.ofSeconds(30)).build();
    }

    static JsonNode get(WebTestClient http, String uri, Object... vars) {
        return http.get().uri(uri, vars).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    static JsonNode post(WebTestClient http, Object body, String uri, Object... vars) {
        return http.post().uri(uri, vars).bodyValue(body).exchange().expectStatus().is2xxSuccessful().expectBody(JsonNode.class).returnResult().getResponseBody();
    }

    @Test
    void aPublishedWorkflowAJobAndItsEvidenceSurviveARestart(@TempDir Path dir) throws Exception {
        String changeRequest;
        String jobId;
        JsonNode evidence;
        JsonNode job;
        try (ConfigurableApplicationContext ctx = start(dir)) {
            assertThat(ctx.getBean(WorkflowStorePort.class)).isInstanceOf(com.iaf.platform.workflows.adapters.outbound.JdbcWorkflowStore.class);
            assertThat(ctx.getBean(ProjectionStore.class)).isInstanceOf(com.iaf.platform.runs.adapters.outbound.JdbcProjectionStore.class);
            assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(com.iaf.platform.audit.adapters.outbound.JdbcEvidenceStore.class);
            assertThat(ctx.getBean(TelemetryPort.class)).isInstanceOf(com.iaf.platform.common.adapters.telemetry.JdbcTelemetry.class);
            WebTestClient http = http(ctx);

            post(http, Map.of("yaml", YAML), "/api/v1/workflows");
            changeRequest = post(http, Map.of("strategy", "all"), "/api/v1/workflows/{name}:publish", "thing-refresh").get("id").asText();
            post(http, Map.of(), "/api/v1/change-requests/{id}:merge", changeRequest);
            jobId = post(http, Map.of("workflow", "thing-refresh", "input", Map.of()), "/api/v1/jobs").get("id").asText();

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            job = get(http, "/api/v1/jobs/{id}", jobId);
            while (!"succeeded".equals(job.path("status").asText()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
                job = get(http, "/api/v1/jobs/{id}", jobId);
            }
            assertThat(job.path("status").asText()).isEqualTo("succeeded");

            // a record is sealed once its closing entry is in, which can land just after the job reads as succeeded
            evidence = get(http, "/api/v1/audit/{jobId}", jobId);
            while (evidence.path("sealed").isMissingNode() && System.nanoTime() < deadline) {
                post(http, Map.of(), "/api/v1/audit:seal");
                Thread.sleep(100);
                evidence = get(http, "/api/v1/audit/{jobId}", jobId);
            }
            assertThat(evidence.path("entries")).isNotEmpty();
            assertThat(evidence.path("sealed").path("signature").asText()).isNotBlank();
            assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).as("statements on Netty or timer threads").isZero();
        }

        try (ConfigurableApplicationContext ctx = start(dir)) {
            WebTestClient http = http(ctx);

            JsonNode workflow = get(http, "/api/v1/workflows/{name}", "thing-refresh");
            assertThat(workflow.toString()).contains("\"published\"").contains("0.1.0");
            assertThat(get(http, "/api/v1/change-requests/{id}", changeRequest).path("status").asText()).isEqualTo("merged");

            JsonNode again = get(http, "/api/v1/jobs/{id}", jobId);
            assertThat(again).isEqualTo(job);
            assertThat(get(http, "/api/v1/jobs?workflow={w}", "thing-refresh").findValuesAsText("id")).contains(jobId);

            List<ServerSentEvent<JsonNode>> events = http.get().uri("/api/v1/jobs/{id}/events", jobId).accept(MediaType.TEXT_EVENT_STREAM).exchange()
                    .expectStatus().isOk().returnResult(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {
                    }).getResponseBody().collectList().block(Duration.ofSeconds(10));
            assertThat(events).isNotEmpty();
            assertThat(events.get(events.size() - 1).data().toString()).contains("job.finished");

            assertThat(get(http, "/api/v1/audit/{jobId}", jobId)).isEqualTo(evidence);
            JsonNode verification = get(http, "/api/v1/audit/{jobId}:verify", jobId);
            assertThat(verification.path("valid").asBoolean()).isTrue();
            assertThat(verification.path("seal").asText()).isEqualTo("verified");
            assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
        }
    }

    @Test
    void theDemoHistoryIsWrittenOnTheFirstStartOnlyAndKept(@TempDir Path dir) {
        Map<String, Long> seeded;
        Set<String> jobs;
        Set<String> releases;
        Set<String> changeRequests;
        try (ConfigurableApplicationContext ctx = start(dir, true)) {
            seeded = rows(ctx);
            assertThat(seeded).allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
            WebTestClient http = http(ctx);
            jobs = ids(get(http, "/api/v1/jobs?limit={n}", 100_000));
            releases = ids(get(http, "/api/v1/releases"));
            changeRequests = ids(get(http, "/api/v1/change-requests"));
            assertThat(jobs).isNotEmpty();
            assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
        }

        try (ConfigurableApplicationContext ctx = start(dir, true)) {
            assertThat(rows(ctx)).isEqualTo(seeded);
            WebTestClient http = http(ctx);
            assertThat(ids(get(http, "/api/v1/jobs?limit={n}", 100_000))).isEqualTo(jobs);
            assertThat(ids(get(http, "/api/v1/releases"))).isEqualTo(releases);
            assertThat(ids(get(http, "/api/v1/change-requests"))).isEqualTo(changeRequests);
            assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
        }
    }
}
