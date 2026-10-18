package com.iaf.platform.common.adapters.mongo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.Banner;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.PlatformApplication;
import com.iaf.platform.workflows.adapters.outbound.MongoWorkflowStore;
import com.iaf.platform.workflows.ports.outbound.WorkflowStorePort;

/**
 * The whole application with the {@code mongo} profile, started twice on the same database of the
 * MongoDB named by {@value TestMongo#MONGO_URI}: what was written before the restart is still there
 * after it, and none of it needed a call on a non-blocking thread.
 */
@EnabledIfEnvironmentVariable(named = TestMongo.MONGO_URI, matches = ".+")
class MongoProfileRestartTest {

    static final String YAML = """
            apiVersion: iaf.platform/v1
            kind: Workflow
            metadata: { name: thing-refresh, title: Refresh a thing, noun: thing, verb: refresh, owner: team-compute }
            policy: { rbac: [ops] }
            workflow:
              do:
                - say:
                    notify: { channel: "#x", message: "hi {{ $job.requester }}" }
            """;

    final String database = TestMongo.freshName();

    @AfterEach
    void drop() {
        TestMongo.client().getDatabase(database).drop();
    }

    /**
     * Arguments, not default properties, so they win over the profile's own settings in
     * application.yml. The fixed signing key is a test value, so a seal made before the restart
     * still verifies after it.
     */
    ConfigurableApplicationContext start(boolean demoHistory) {
        return new SpringApplicationBuilder(PlatformApplication.class)
                .profiles("demo", "mongo")
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .run("--server.port=0",
                        "--spring.data.mongodb.uri=" + TestMongo.uriOf(database),
                        "--platform.content.dir=..",
                        "--platform.demo.seed-history=" + demoHistory,
                        "--platform.audit.signing-key=test-signing-key");
    }

    @Test
    void aSavedWorkflowSurvivesARestart() {
        try (ConfigurableApplicationContext ctx = start(false)) {
            assertThat(ctx.getBean(WorkflowStorePort.class)).isInstanceOf(MongoWorkflowStore.class);
            assertThat(ctx.getBean(MongoHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
            post(http(ctx), Map.of("yaml", YAML), "/api/v1/workflows");
            assertThat(ctx.getBean(Mongo.class).callsOnNonBlockingThreads()).as("calls on Netty or timer threads").isZero();
        }

        try (ConfigurableApplicationContext ctx = start(false)) {
            JsonNode workflow = get(http(ctx), "/api/v1/workflows/{name}", "thing-refresh");
            assertThat(workflow.toString()).contains("0.1.0").contains("Refresh a thing");
            assertThat(ctx.getBean(Mongo.class).callsOnNonBlockingThreads()).isZero();
        }
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
