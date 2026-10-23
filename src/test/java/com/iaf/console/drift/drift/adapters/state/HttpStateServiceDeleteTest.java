package com.iaf.console.drift.drift.adapters.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.sun.net.httpserver.HttpServer;

/** The delete request (requestDelete of the state contract) as the state service client sends it, and a refusal. */
class HttpStateServiceDeleteTest {

    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";
    private static final String PATH = "/v1/wfrn/" + WFRN + ":request-delete";

    record Seen(String path, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String answer;
    private HttpStateService client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            seen.add(new Seen(URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8), headers,
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new HttpStateService(WebClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), (request, next) -> next.exchange(request),
                Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theDeleteRequestNamesTheWorkflowTheReasonAndThePerson() throws IOException {
        answer = "{\"wfrn\":\"" + WFRN + "\",\"accountId\":\"123456789012\",\"region\":\"global\",\"type\":\"vm\",\"state\":\"deleting\",\"version\":5}";
        StateResource r = client.requestDelete(WFRN, "delete-vm", "idle for two weeks", "dana", "req-7").block();
        assertThat(r.state()).isEqualTo("deleting");
        assertThat(r.version()).isEqualTo(5);

        Seen request = seen.get(0);
        assertThat(request.path()).isEqualTo(PATH);
        JsonNode body = Json.MAPPER.readTree(request.body());
        assertThat(body.path("workflow").asText()).isEqualTo("delete-vm");
        assertThat(body.path("reason").asText()).isEqualTo("idle for two weeks");
        assertThat(body.path("actor").asText()).isEqualTo("dana");
        assertThat(request.headers()).containsEntry("idempotency-key", "drift-delete:req-7").containsEntry("x-console-request-id", "req-7");

        client.requestDelete(WFRN, "delete-vm", null, "dana", null).block();
        Seen bare = seen.get(1);
        assertThat(Json.MAPPER.readTree(bare.body()).has("reason")).isFalse();
        assertThat(bare.headers()).doesNotContainKeys("idempotency-key", "x-console-request-id");
    }

    @Test
    void aBusyResourceIsA409() {
        status.set(409);
        answer = "{\"error\":\"resource.busy\",\"detail\":{\"state\":\"updating\"}}";
        assertThatThrownBy(() -> client.requestDelete(WFRN, "delete-vm", "idle", "dana", "req-8").block())
                .isInstanceOfSatisfying(StateServicePort.StateError.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.conflict()).isTrue();
                    assertThat(e.error()).isEqualTo("resource.busy");
                    assertThat(e.detailText("state")).isEqualTo("updating");
                });
    }
}
