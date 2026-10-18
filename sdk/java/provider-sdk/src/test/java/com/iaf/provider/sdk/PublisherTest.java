package com.iaf.provider.sdk;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

class PublisherTest {

    private static final String TOKEN = "iaf_test-token";

    private HttpServer platform;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final Provider provider = new Inventory().provider().build();

    @BeforeEach
    void start() throws IOException {
        platform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        platform.start();
    }

    @AfterEach
    void stop() {
        platform.stop(0);
    }

    private Map<String, String> env() {
        return Map.of(Publisher.URL_ENV, "http://127.0.0.1:" + platform.getAddress().getPort(), Publisher.TOKEN_ENV, TOKEN);
    }

    private void answer(String path, int status, String body) {
        platform.createContext(path, exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            bodies.add(Json.MAPPER.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = body.getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    @Test
    void writeCreatesTheFoldersAndTheProviderFile(@TempDir Path dir) throws IOException {
        Path file = Publisher.write(provider, dir.resolve("providers/team-platform/inventory.yaml"));

        assertThat(Files.readString(file)).isEqualTo(provider.toYaml());
    }

    @Test
    void submitChecksTheFileThenPublishesItAsTheServiceAccount() throws Exception {
        answer("/api/v1/providers:validate", 200, "{\"checks\":[],\"problems\":[{\"level\":\"warning\",\"code\":\"alerts.channel\",\"message\":\"Set alerts.channel\"}]}");
        answer("/api/v1/providers:publish", 202, "{\"id\":\"cr-42\",\"status\":\"open\",\"links\":{}}");

        Publisher.Submission s = Publisher.submit(provider, env());

        assertThat(s).isEqualTo(new Publisher.Submission("cr-42", "open"));
        assertThat(calls).containsExactly(
                "POST /api/v1/providers:validate Bearer " + TOKEN,
                "POST /api/v1/providers:publish Bearer " + TOKEN);
        assertThat(bodies).allSatisfy(b -> assertThat(b.path("yaml").asText()).isEqualTo(provider.toYaml()));
    }

    @Test
    void aCheckErrorStopsTheSubmitBeforeAnythingIsPublished() {
        answer("/api/v1/providers:validate", 200,
                "{\"checks\":[],\"problems\":[{\"level\":\"error\",\"code\":\"metadata.owner\",\"field\":\"metadata.owner\",\"message\":\"owner is a team\"}]}");
        answer("/api/v1/providers:publish", 202, "{\"id\":\"cr-1\"}");

        assertThatThrownBy(() -> Publisher.submit(provider, env()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("metadata.owner at metadata.owner: owner is a team");
        assertThat(calls).hasSize(1);
    }

    @Test
    void aRefusedPublishSaysWhyWithoutRepeatingTheToken() {
        answer("/api/v1/providers:validate", 200, "{\"checks\":[],\"problems\":[]}");
        answer("/api/v1/providers:publish", 403, "{\"error\":\"Only team-platform may publish provider inventory\"}");

        assertThatThrownBy(() -> Publisher.submit(provider, env()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("Only team-platform may publish provider inventory")
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void submittingNeedsAServiceAccountTokenFromTheEnvironment() {
        assertThatThrownBy(() -> Publisher.submit(provider, Map.of(Publisher.URL_ENV, "http://127.0.0.1:1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IAF_PLATFORM_TOKEN");
        assertThat(calls).isEmpty();
    }
}
