package com.iaf.console.drift.auth.adapters.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.drift.auth.domain.ConsolePrincipal;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

class IdentityAuthorizationTest {

    private static final ConsolePrincipal DANA = new ConsolePrincipal("user_dana", "123456789012", "global", List.of("members"), List.of("operator"),
            "console-bff");

    private HttpServer identity;
    private final AtomicReference<JsonNode> asked = new AtomicReference<>();
    private final AtomicReference<String> answer = new AtomicReference<>("{\"decisions\":[{\"allowed\":true}],\"version\":1}");
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        identity = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        identity.createContext("/v1/authorize", exchange -> {
            asked.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        identity.start();
    }

    @AfterEach
    void stop() {
        identity.stop(0);
    }

    private IdentityAuthorization authorization() {
        return new IdentityAuthorization(WebClient.builder(), "http://127.0.0.1:" + identity.getAddress().getPort(), ExchangeFilterFunction.ofRequestProcessor(Mono::just),
                Duration.ofSeconds(5));
    }

    @Test
    void asksOneCheckForThePersonAndReadsTheDecision() {
        String wfrn = "wfrn:wf:compute:global:123456789012:vm/vm-01";
        assertThat(authorization().allowed(DANA, "compute:update", wfrn).block()).isTrue();
        JsonNode body = asked.get();
        assertThat(body.path("principal").path("userId").asText()).isEqualTo("user_dana");
        assertThat(body.path("principal").path("accountId").asText()).isEqualTo("123456789012");
        assertThat(body.path("principal").path("groups").get(0).asText()).isEqualTo("members");
        assertThat(body.path("checks").get(0).path("action").asText()).isEqualTo("compute:update");
        assertThat(body.path("checks").get(0).path("resource").asText()).isEqualTo(wfrn);

        answer.set("{\"decisions\":[{\"allowed\":false,\"reason\":\"no statement allows it\"}],\"version\":1}");
        assertThat(authorization().allowed(DANA, "compute:delete", wfrn).block()).isFalse();
    }

    @Test
    void anIdentityServiceThatFailsRefusesTheWrite() {
        status = 503;
        answer.set("{\"error\":\"down\"}");
        assertThat(catchThrowable(() -> authorization().allowed(DANA, "compute:update", "wfrn:wf:compute:global:123456789012:vm/vm-01").block()))
                .isNotNull();
    }
}
