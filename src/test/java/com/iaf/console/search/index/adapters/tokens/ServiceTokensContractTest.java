package com.iaf.console.search.index.adapters.tokens;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.iaf.console.search.TestClock;
import com.iaf.console.search.support.StubTokenServer;

import reactor.core.publisher.Mono;

/** What the client sends the BFF matches {@code issueServiceToken} in the pinned console-bff.yaml. */
class ServiceTokensContractTest {

    private static JsonNode contract() throws IOException {
        try (InputStream in = ServiceTokensContractTest.class.getResourceAsStream("/contracts/openapi/console-bff.yaml")) {
            return new ObjectMapper(new YAMLFactory()).readTree(in);
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void theRequestIsTheContractsForm() throws IOException {
        JsonNode contract = contract();
        JsonNode operation = contract.path("paths").path("/internal/v1/token").path("post");
        assertThat(operation.path("operationId").asText()).isEqualTo("issueServiceToken");
        JsonNode content = operation.path("requestBody").path("content").path("application/x-www-form-urlencoded");
        assertThat(content.path("encoding").path("audience").path("explode").asBoolean()).as("audience is sent once per service").isTrue();
        JsonNode request = contract.path("components").path("schemas").path("ServiceTokenRequest");
        assertThat(content.path("schema").path("$ref").asText()).endsWith("/ServiceTokenRequest");
        assertThat(strings(request.path("properties").path("grant_type").path("enum"))).contains(ServiceTokens.GRANT_TYPE);
        assertThat(strings(request.path("properties").path("client_assertion_type").path("enum"))).contains(ServiceTokens.ASSERTION_TYPE);

        try (StubTokenServer bff = StubTokenServer.start()) {
            TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
            new ServiceTokens(WebClient.create(), bff.url(), () -> Mono.just("assertion"), ServiceCredentials.SERVICE, clock)
                    .token("console-state-service").block();
            MultiValueMap<String, String> sent = bff.forms().get(0);
            assertThat(sent.keySet()).as("every required field").containsAll(strings(request.path("required")));
            assertThat(request.path("properties").fieldNames()).toIterable().as("and nothing the contract does not name").containsAll(sent.keySet());
        }
        JsonNode token = contract.path("components").path("schemas").path("ServiceToken");
        assertThat(strings(token.path("required"))).contains("access_token", "token_type", "expires_in");
        assertThat(strings(token.path("properties").path("token_type").path("enum"))).containsExactly("Bearer");
    }
}
