package com.iaf.platform.common.adapters.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import com.iaf.platform.common.ports.TelemetryPort.CallSample;
import com.iaf.platform.providers.adapters.outbound.JdbcProviderStore;
import com.iaf.platform.providers.adapters.outbound.JdbcReviewEvidence;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.providers.ports.inbound.ProviderQueries.Use;
import com.iaf.platform.providers.ports.outbound.ProviderStorePort;
import com.iaf.platform.providers.ports.outbound.ReviewEvidencePort;
import com.iaf.platform.releases.adapters.outbound.JdbcReleaseStore;
import com.iaf.platform.releases.ports.outbound.ReleaseStore;
import com.iaf.platform.runs.ports.outbound.ProjectionStore;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

/**
 * The whole application with the {@code postgres} profile, started twice on the same database: a
 * workflow published through a change request, the job that ran it and the job's sealed evidence
 * are still there after the restart, so are a provider's canary candidate, its releases, the
 * wizard's review evidence and the calls' provider versions, the demo history is written on the
 * first start only, and none of it needed a statement on a non-blocking thread. On an H2 file
 * database in PostgreSQL mode always, and on PostgreSQL too when {@value TestDatabase#POSTGRES_URL}
 * names one.
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

    /** An http provider the wizard can prove in the demo profile (hosts under example.com answer the probe). */
    static final String PROVIDER = """
            apiVersion: iaf.platform/v1
            kind: Provider
            metadata: { name: thing, title: Thing API, owner: team-compute, version: 1.0.0 }
            spec:
              type: http
              endpoints: { dev: https://thing-dev.example.com/api, test: https://thing-test.example.com/api, prod: https://thing.example.com/api }
              auth: { type: none }
              actions:
                - id: thing.get
                  title: Get a thing
                  operation: { method: GET, path: "/things/{id}" }
                  effect: read
                  retry: safe
                  input: { type: object, required: [id], properties: { id: { type: string } } }
                  output: { type: object, properties: { id: { type: string }, state: { type: string } } }
                  example: { id: thing-1, state: ready }
                  sample: { id: thing-1, state: ready }
              errors:
                - { match: { status: 404 }, class: not_found, retry: false }
              slo: { successRate: 99.0, p95Ms: 800 }
            """;
    static final String PROVIDER_V2 = PROVIDER.replace("version: 1.0.0", "version: 1.1.0").replace("p95Ms: 800", "p95Ms: 900");

    abstract static class OnDatabase {

        /** The JDBC URL of the database both starts share. */
        abstract String url();

        /**
         * Arguments, not default properties: the postgres profile's own datasource settings in
         * application.yml would win over those. A user named in the URL wins over the username.
         * The fixed signing key is a test value, so a seal made before the restart still verifies
         * after it.
         */
        ConfigurableApplicationContext start(boolean demoHistory) {
            return new SpringApplicationBuilder(PlatformApplication.class)
                    .profiles("demo", "postgres")
                    .bannerMode(Banner.Mode.OFF)
                    .logStartupInfo(false)
                    .run("--server.port=0",
                            "--spring.datasource.url=" + url(),
                            "--spring.datasource.username=sa",
                            "--spring.datasource.password=",
                            "--platform.content.dir=..",
                            "--platform.demo.seed-history=" + demoHistory,
                            "--platform.audit.signing-key=test-signing-key");
        }

        @Test
        void aPublishedWorkflowAJobAndItsEvidenceSurviveARestart() throws Exception {
            String changeRequest;
            String jobId;
            JsonNode evidence;
            JsonNode job;
            try (ConfigurableApplicationContext ctx = start(false)) {
                assertThat(ctx.getBean(WorkflowStorePort.class)).isInstanceOf(com.iaf.platform.workflows.adapters.outbound.JdbcWorkflowStore.class);
                assertThat(ctx.getBean(ProjectionStore.class)).isInstanceOf(com.iaf.platform.runs.adapters.outbound.JdbcProjectionStore.class);
                assertThat(ctx.getBean(EvidenceStore.class)).isInstanceOf(com.iaf.platform.audit.adapters.outbound.JdbcEvidenceStore.class);
                assertThat(ctx.getBean(TelemetryPort.class)).isInstanceOf(JdbcTelemetry.class);
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

            try (ConfigurableApplicationContext ctx = start(false)) {
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
        void theDemoHistoryIsWrittenOnTheFirstStartOnlyAndKept() {
            Map<String, Long> seeded;
            Set<String> jobs;
            Set<String> releases;
            Set<String> changeRequests;
            try (ConfigurableApplicationContext ctx = start(true)) {
                seeded = rows(ctx);
                assertThat(seeded).allSatisfy((table, rows) -> assertThat(rows).as(table).isPositive());
                WebTestClient http = http(ctx);
                jobs = ids(get(http, "/api/v1/jobs?limit={n}", 100_000));
                releases = ids(get(http, "/api/v1/releases"));
                changeRequests = ids(get(http, "/api/v1/change-requests"));
                assertThat(jobs).isNotEmpty();
                assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
            }

            try (ConfigurableApplicationContext ctx = start(true)) {
                assertThat(rows(ctx)).isEqualTo(seeded);
                WebTestClient http = http(ctx);
                assertThat(ids(get(http, "/api/v1/jobs?limit={n}", 100_000))).isEqualTo(jobs);
                assertThat(ids(get(http, "/api/v1/releases"))).isEqualTo(releases);
                assertThat(ids(get(http, "/api/v1/change-requests"))).isEqualTo(changeRequests);
                assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
            }
        }

        @Test
        void aProviderCanaryItsEvidenceAndItsCallsSurviveARestart() {
            Set<String> releases;
            try (ConfigurableApplicationContext ctx = start(false)) {
                assertThat(ctx.getBean(ProviderStorePort.class)).isInstanceOf(JdbcProviderStore.class);
                assertThat(ctx.getBean(ReleaseStore.class)).isInstanceOf(JdbcReleaseStore.class);
                assertThat(ctx.getBean(ReviewEvidencePort.class)).isInstanceOf(JdbcReviewEvidence.class);
                WebTestClient http = http(ctx);

                // the wizard proves the connection and the one action; the review judges the publish by that
                JsonNode probed = post(http, Map.of("endpoints", Map.of("dev", "https://thing-dev.example.com/api", "test", "https://thing-test.example.com/api"),
                        "auth", Map.of("type", "none"), "provider", "thing"), "/api/v1/providers:test-connection");
                assertThat(probed.path("results")).hasSize(2).allSatisfy(r -> assertThat(r.path("ok").asBoolean()).isTrue());
                JsonNode tried = post(http, Map.of("yaml", PROVIDER, "action", "thing.get", "environment", "dev", "input", Map.of("id", "thing-1")),
                        "/api/v1/providers:try");
                assertThat(tried.path("ok").asBoolean()).as(tried.toString()).isTrue();

                // 1.0.0 to every call at once, then 1.1.0 kept beside it as a canary
                merge(http, post(http, Map.of("yaml", PROVIDER, "strategy", "all"), "/api/v1/providers:publish"));
                merge(http, post(http, Map.of("yaml", PROVIDER_V2, "strategy", "canary"), "/api/v1/providers:publish"));
                assertThat(get(http, "/api/v1/providers/{name}", "thing").path("version").asText()).isEqualTo("1.0.0");
                releases = ids(get(http, "/api/v1/releases?provider={p}", "thing"));
                assertThat(releases).hasSize(2);
                assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
            }

            try (ConfigurableApplicationContext ctx = start(false)) {
                WebTestClient http = http(ctx);

                assertThat(get(http, "/api/v1/providers/{name}", "thing").path("version").asText()).isEqualTo("1.0.0");
                JsonNode listed = get(http, "/api/v1/releases?kind={k}", "provider");
                assertThat(ids(listed)).isEqualTo(releases);
                List<String> statuses = new ArrayList<>();
                listed.forEach(r -> statuses.add(r.path("status").asText()));
                assertThat(statuses).containsExactlyInAnyOrder("promoted", "canary");

                ProviderQueries providers = ctx.getBean(ProviderQueries.class);
                assertThat(providers.provider("thing").map(d -> d.metadata().version()).block()).isEqualTo("1.0.0");
                assertThat(providers.provider("thing").contextWrite(c -> Use.with(c, new Use("job_1", "dev", false))).map(d -> d.metadata().version()).block())
                        .as("a sandbox job's call is routed to the kept candidate").isEqualTo("1.1.0");

                List<CallSample> calls = ctx.getBean(TelemetryPort.class).samples("thing", null);
                assertThat(calls).extracting(CallSample::action, CallSample::environment, CallSample::ok, CallSample::providerVersion)
                        .contains(tuple("thing.get", "dev", true, "1.0.0"));

                JsonNode validation = post(http, Map.of("yaml", PROVIDER_V2), "/api/v1/providers:validate");
                assertThat(validation.path("review").path("ready").asBoolean()).as(validation.toString()).isTrue();
                assertThat(check(validation, "connection")).isEqualTo("passed");
                assertThat(check(validation, "tried")).isEqualTo("passed");
                assertThat(ctx.getBean(Jdbc.class).callsOnNonBlockingThreads()).isZero();
            }
        }
    }

    @Nested
    class OnH2 extends OnDatabase {
        @TempDir
        Path dir;

        @Override
        String url() {
            return "jdbc:h2:file:" + dir.resolve("platform") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        }
    }

    @Nested
    @EnabledIfEnvironmentVariable(named = TestDatabase.POSTGRES_URL, matches = ".+")
    class OnPostgres extends OnDatabase {
        @RegisterExtension
        final TestDatabase db = TestDatabase.postgres();

        @Override
        String url() {
            return db.url();
        }
    }

    /** Rows in the tables the demo history and the content seed fill, once the telemetry queue is written. */
    static Map<String, Long> rows(ConfigurableApplicationContext ctx) {
        ctx.getBean(JdbcTelemetry.class).flush();
        Jdbc jdbc = ctx.getBean(Jdbc.class);
        Map<String, Long> rows = new TreeMap<>();
        for (String table : List.of("workflow_versions", "change_requests", "providers", "provider_versions", "releases", "resources", "runs_jobs",
                "runs_events", "evidence_records", "evidence_entries", "telemetry_samples")) {
            rows.put(table, jdbc.first("SELECT COUNT(*) FROM " + table, (rs, i) -> rs.getLong(1)).orElse(0L));
        }
        return rows;
    }

    static Set<String> ids(JsonNode list) {
        Set<String> ids = new TreeSet<>();
        list.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    /** Merges the change request a publish opened. */
    static void merge(WebTestClient http, JsonNode accepted) {
        post(http, Map.of(), "/api/v1/change-requests/{id}:merge", accepted.path("id").asText());
    }

    /** The status of one review check of a validation. */
    static String check(JsonNode validation, String id) {
        for (JsonNode c : validation.path("review").path("checks")) {
            if (id.equals(c.path("id").asText())) {
                return c.path("status").asText();
            }
        }
        return null;
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
}
