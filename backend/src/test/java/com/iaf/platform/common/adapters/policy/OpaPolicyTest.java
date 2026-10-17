package com.iaf.platform.common.adapters.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.common.ports.PolicyPort.Phase;
import com.iaf.platform.common.ports.PolicyPort.PolicyInput;
import com.iaf.platform.common.ports.PolicyPort.Rule;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.testing.LocalHttp;
import com.sun.net.httpserver.HttpServer;

import reactor.test.StepVerifier;

class OpaPolicyTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Identity MATEO = new Identity("mateo", "Mateo", "m@example.com", List.of("team-compute"), List.of("ops"), false, Identity.Kind.USER);

    HttpServer server;
    AtomicReference<String> received = new AtomicReference<>();
    OpaPolicy policy;

    @BeforeEach
    void start() throws IOException {
        server = LocalHttp.start(ex -> {
            String key = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            received.set(LocalHttp.body(ex));
            switch (key) {
                case "POST /v1/data/platform/invoke" -> LocalHttp.respond(ex, 200,
                        "{\"result\":{\"allow\":true,\"needsApproval\":true,\"rules\":[{\"id\":\"approval.prod\",\"result\":\"needs-approval\",\"message\":\"Prod needs a sign-off\"}]}}");
                case "POST /v1/data/platform/publish" -> LocalHttp.respond(ex, 200, "{\"result\":{\"allow\":false,\"rules\":[{\"id\":\"owner\",\"result\":\"deny\"}]}}");
                case "POST /v1/data/platform/resource-access" -> LocalHttp.respond(ex, 200, "{\"result\":{\"allow\":true}}");
                case "POST /v1/data/platform/step" -> LocalHttp.respond(ex, 200, "{}");
                default -> LocalHttp.respond(ex, 404, "{}");
            }
        });
        PlatformProperties props = new PlatformProperties();
        props.getPolicy().setOpaUrl(LocalHttp.url(server) + "/v1/data/platform/");
        policy = new OpaPolicy(WebClient.builder(), props);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static PolicyInput input(Phase phase) throws IOException {
        return new PolicyInput(phase, MATEO, "vm-restart", "1.2.0", JSON.readTree("{\"vmId\":\"vm-1\"}"), JSON.readTree("{\"destructive\":false}"));
    }

    @Test
    void postsTheInputAndMapsTheResult() throws IOException {
        StepVerifier.create(policy.evaluate(input(Phase.INVOKE)))
                .assertNext(d -> {
                    assertThat(d.decision()).isEqualTo("needs-approval");
                    assertThat(d.engine()).isEqualTo("opa");
                    assertThat(d.rules()).extracting(Rule::id).containsExactly("approval.prod");
                    assertThat(d.rules().get(0).message()).isEqualTo("Prod needs a sign-off");
                })
                .verifyComplete();
        JsonNode sent = JSON.readTree(received.get());
        assertThat(sent.path("input").path("phase").asText()).isEqualTo("invoke");
        assertThat(sent.path("input").path("who").path("id").asText()).isEqualTo("mateo");
        assertThat(sent.path("input").path("who").path("teams").get(0).asText()).isEqualTo("team-compute");
        assertThat(sent.path("input").path("workflow").asText()).isEqualTo("vm-restart");
        assertThat(sent.path("input").path("input").path("vmId").asText()).isEqualTo("vm-1");
        assertThat(sent.path("input").path("context").path("destructive").asBoolean()).isFalse();
        assertThat(policy.engine()).isEqualTo("opa");
    }

    @Test
    void deniesWhenTheEngineSaysNoOrSaysNothing() throws IOException {
        StepVerifier.create(policy.evaluate(input(Phase.PUBLISH)))
                .assertNext(d -> {
                    assertThat(d.decision()).isEqualTo("deny");
                    assertThat(d.rules()).extracting(Rule::id).containsExactly("owner");
                })
                .verifyComplete();
        StepVerifier.create(policy.evaluate(input(Phase.RESOURCE_ACCESS))).assertNext(d -> assertThat(d.decision()).isEqualTo("allow")).verifyComplete();
        StepVerifier.create(policy.evaluate(input(Phase.STEP)))
                .assertNext(d -> {
                    assertThat(d.decision()).isEqualTo("deny");
                    assertThat(d.rules()).extracting(Rule::id).containsExactly("opa.undefined");
                })
                .verifyComplete();
    }

    @Test
    void failsClosedWhenTheEngineIsUnreachable() throws IOException {
        server.stop(0);
        StepVerifier.create(policy.evaluate(input(Phase.INVOKE)))
                .assertNext(d -> {
                    assertThat(d.decision()).isEqualTo("deny");
                    assertThat(d.rules()).extracting(Rule::id).containsExactly("opa.unreachable");
                })
                .verifyComplete();
    }
}
