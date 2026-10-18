package com.iaf.platform.runs.adapters.outbound.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.ports.CredentialsPort;
import com.iaf.platform.config.RunnersProperties;
import com.iaf.platform.providers.ports.inbound.ProviderQueries;
import com.iaf.platform.runs.ports.outbound.AutomationRunnerPort.RunRequest;
import com.iaf.platform.runs.ports.outbound.ProviderCallPort.ProviderCallException;
import com.iaf.platform.testing.LocalHttp;
import com.iaf.platform.workflows.domain.Step;
import com.iaf.platform.workflows.domain.StepType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class HttpStepRunnerTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "s3cr3t-token-value";

    HttpServer server;
    String base;
    final AtomicInteger hits = new AtomicInteger();
    final Map<String, String> seen = new ConcurrentHashMap<>();
    final List<String> logged = new CopyOnWriteArrayList<>();
    HttpStepRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        server = LocalHttp.start(this::handle);
        base = LocalHttp.url(server);
        CredentialsPort credentials = mock(CredentialsPort.class);
        when(credentials.resolve(anyString())).thenReturn(Mono.empty());
        when(credentials.resolve("vault:http/token")).thenReturn(Mono.just(TOKEN));
        when(credentials.resolve("vault:http/basic")).thenReturn(Mono.just("dXNlcjpwYXNz"));
        ProviderQueries providers = mock(ProviderQueries.class);
        when(providers.all()).thenReturn(Flux.empty());
        runner = new HttpStepRunner(WebClient.builder(), credentials, providers, new RunnersProperties());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        hits.incrementAndGet();
        String path = x.getRequestURI().getPath();
        seen.put("method", x.getRequestMethod());
        seen.put("path", path);
        x.getRequestHeaders().forEach((k, v) -> seen.put("header:" + k.toLowerCase(), v.get(0)));
        seen.put("body", LocalHttp.body(x));
        switch (path) {
            case "/things" -> LocalHttp.respond(x, 200, "{\"ok\": true, \"items\": [1, 2]}");
            case "/text" -> {
                x.getResponseHeaders().add("Content-Type", "text/plain");
                byte[] b = "plain words".getBytes();
                x.sendResponseHeaders(200, b.length);
                x.getResponseBody().write(b);
                x.close();
            }
            case "/echo-auth" -> LocalHttp.respond(x, 401, "{\"error\": \"bad token " + x.getRequestHeaders().getFirst("Authorization") + "\"}");
            case "/missing" -> LocalHttp.respond(x, 404, "{\"error\": \"no such thing\"}");
            case "/busy" -> LocalHttp.respond(x, 503, "");
            case "/slow-down" -> LocalHttp.respond(x, 429, "");
            case "/start" -> {
                x.getResponseHeaders().add("Location", "/things");
                x.sendResponseHeaders(302, -1);
                x.close();
            }
            case "/sleep" -> {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                LocalHttp.respond(x, 200, "{}");
            }
            default -> LocalHttp.respond(x, 500, "{}");
        }
    }

    RunRequest request(String json, String timeout, boolean dryRun) throws IOException {
        ObjectNode with = (ObjectNode) JSON.readTree(json.replace("BASE", base));
        Step step = new Step("call", "Call", StepType.HTTP, with, null, null, null, timeout, true, null, null, null, null, null, null, null);
        return new RunRequest("job-1", 2, StepType.HTTP, step, with, "dev", dryRun, false);
    }

    JsonNode run(String json) throws IOException {
        return runner.run(request(json, null, false), logged::add).block(Duration.ofSeconds(30));
    }

    @Test
    void aGetAnswersTheStatusAndTheParsedJsonBody() throws Exception {
        JsonNode out = run("{\"url\": \"BASE/things?page=2\"}");

        assertThat(out.path("status").asInt()).isEqualTo(200);
        assertThat(out.path("body")).isEqualTo(JSON.readTree("{\"ok\": true, \"items\": [1, 2]}"));
        assertThat(seen).containsEntry("method", "GET");
        assertThat(logged).containsExactly("GET " + base + "/things -> 200");
    }

    @Test
    void aBodyThatIsNotJsonComesBackAsText() throws Exception {
        assertThat(run("{\"url\": \"BASE/text\"}").path("body").asText()).isEqualTo("plain words");
    }

    @Test
    void aBodyIsSentAsJsonWithTheMethodAndTheHeaders() throws Exception {
        run("{\"method\": \"put\", \"url\": \"BASE/things\", \"headers\": {\"X-Trace\": \"abc\"}, \"body\": {\"a\": 1}}");

        assertThat(seen).containsEntry("method", "PUT").containsEntry("header:x-trace", "abc").containsEntry("body", "{\"a\":1}");
        assertThat(seen.get("header:content-type")).startsWith("application/json");
    }

    @Test
    void theCredentialBecomesABearerTokenThatNeverShowsInAMessage() throws Exception {
        run("{\"url\": \"BASE/things\", \"credential\": \"vault:http/token\"}");
        assertThat(seen).containsEntry("header:authorization", "Bearer " + TOKEN);

        assertThatThrownBy(() -> run("{\"url\": \"BASE/echo-auth\", \"credential\": \"vault:http/token\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("unauthorized");
                    assertThat(e.getMessage()).contains("Bearer ****").doesNotContain(TOKEN);
                });
        assertThat(String.join("\n", logged)).doesNotContain(TOKEN);
    }

    @Test
    void aHeaderValueMayBeAVaultReferenceWithOrWithoutAScheme() throws Exception {
        run("{\"url\": \"BASE/things\", \"headers\": {\"X-Api-Key\": \"vault:http/token\", \"Authorization\": \"Basic vault:http/basic\"}}");

        assertThat(seen).containsEntry("header:x-api-key", TOKEN).containsEntry("header:authorization", "Basic dXNlcjpwYXNz");
    }

    @Test
    void aMissingSecretFailsTheStepBeforeAnyCall() {
        assertThatThrownBy(() -> run("{\"url\": \"BASE/things\", \"credential\": \"vault:http/none\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("credential_missing"));
        assertThatThrownBy(() -> run("{\"url\": \"BASE/things\", \"credential\": \"plain-text-secret\"}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("credential is a vault:<path> reference");
        assertThat(hits).hasValue(0);
    }

    @Test
    void aStatusOutside2xxFailsWithTheClassItImplies() {
        assertThatThrownBy(() -> run("{\"url\": \"BASE/missing\"}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("not_found");
            assertThat(e.retryable()).isFalse();
            assertThat(e.status()).isEqualTo(404);
            assertThat(e.getMessage()).isEqualTo("HTTP 404: {\"error\": \"no such thing\"}");
        });
        assertThatThrownBy(() -> run("{\"url\": \"BASE/busy\"}")).isInstanceOfSatisfying(ProviderCallException.class, e -> {
            assertThat(e.errorClass()).isEqualTo("unavailable");
            assertThat(e.retryable()).isTrue();
        });
        assertThatThrownBy(() -> run("{\"url\": \"BASE/slow-down\"}")).isInstanceOfSatisfying(ProviderCallException.class,
                e -> assertThat(e.errorClass()).isEqualTo("rate_limited"));
    }

    @Test
    void aCloudMetadataAddressIsRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> run("{\"url\": \"http://169.254.169.254/latest/meta-data\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> assertThat(e.errorClass()).isEqualTo("refused"));
        assertThatThrownBy(() -> run("{\"url\": \"ftp://example.com/x\"}")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redirectsAreFollowedOnlyByRequestsWithoutSecrets() throws Exception {
        assertThat(run("{\"url\": \"BASE/start\"}").path("body").path("ok").asBoolean()).isTrue();

        assertThatThrownBy(() -> run("{\"url\": \"BASE/start\", \"credential\": \"vault:http/token\"}"))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("redirect");
                    assertThat(e.getMessage()).contains("302").contains("not followed");
                });
    }

    @Test
    void anAnswerSlowerThanTheStepsTimeoutFailsAsATimeout() throws Exception {
        RunRequest r = request("{\"url\": \"BASE/sleep\"}", "500ms", false);

        assertThatThrownBy(() -> runner.run(r, logged::add).block(Duration.ofSeconds(30)))
                .isInstanceOfSatisfying(ProviderCallException.class, e -> {
                    assertThat(e.errorClass()).isEqualTo("timeout");
                    assertThat(e.retryable()).isTrue();
                });
    }

    @Test
    void aDryRunNeverCallsTheServer() throws Exception {
        JsonNode out = runner.run(request("{\"url\": \"BASE/things\", \"credential\": \"vault:http/token\"}", null, true), logged::add)
                .block(Duration.ofSeconds(10));

        assertThat(out).isEqualTo(JSON.readTree("{\"status\": 200, \"body\": {}}"));
        assertThat(hits).hasValue(0);
    }
}
