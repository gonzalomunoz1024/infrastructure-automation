package com.iaf.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.iaf.platform.runs.adapters.outbound.temporal.InterpreterWorkerLifecycle;

/**
 * The whole application as a worker-only instance (the temporal profile, no server reachable):
 * it keeps the actuator's health and metrics, so probes and scrapes work, and serves no API.
 */
@SpringBootTest(properties = {"platform.roles=worker", "platform.temporal.target=127.0.0.1:1"})
@ActiveProfiles({"demo", "temporal"})
@AutoConfigureWebTestClient
@AutoConfigureObservability(tracing = false)
class WorkerRoleApplicationTest {

    @Autowired
    WebTestClient client;

    @Autowired
    ApplicationContext context;

    @Test
    void aWorkerOnlyInstanceAnswersHealthAndMetricsButNotTheApi() {
        assertThat(context.getBeansOfType(InterpreterWorkerLifecycle.class)).hasSize(1);
        client.get().uri("/actuator/health").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
        client.get().uri("/actuator/metrics/jvm.memory.used").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/workflows").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("This instance has the worker role only; instances with the api role serve the API");
    }
}
