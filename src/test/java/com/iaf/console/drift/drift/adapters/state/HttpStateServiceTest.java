package com.iaf.console.drift.drift.adapters.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.auth.adapters.bff.ServiceTokens;
import com.iaf.console.drift.drift.domain.Json;
import com.iaf.console.drift.drift.domain.Resolution;
import com.iaf.console.drift.drift.domain.StateResource;
import com.iaf.console.drift.drift.ports.outbound.StateServicePort;
import com.iaf.console.kit.paging.Page;
import com.sun.net.httpserver.HttpServer;

import reactor.core.publisher.Mono;

/** The state service client against a recorded HTTP server: the requests it sends and how it reads the answers. */
class HttpStateServiceTest {

    record Seen(String method, String path, String query, Map<String, String> headers, String body) {
    }

    private HttpServer server;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Map<String, int[]> statuses = new ConcurrentHashMap<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private HttpStateService client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8);
            Map<String, String> headers = new ConcurrentHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), String.join(",", v)));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(), headers, body));
            String key = exchange.getRequestMethod() + " " + path;
            byte[] answer = answers.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statuses.getOrDefault(key, new int[] {200})[0], answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        // the BFF's token endpoint (hub decision 23): the client calls as console-drift-service with the token it issues
        server.createContext("/internal/v1/token", exchange -> {
            byte[] token = "{\"access_token\":\"svc-token\",\"token_type\":\"Bearer\",\"expires_in\":300}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, token.length);
            exchange.getResponseBody().write(token);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ServiceTokens tokens = new ServiceTokens(WebClient.builder(), URI.create(base + "/internal/v1/token"), HttpStateService.SERVICE,
                () -> Mono.just("projected-sa-token"), Duration.ofSeconds(60), Duration.ofSeconds(5), Clock.systemUTC());
        client = new HttpStateService(WebClient.builder(), base, tokens.forAudience("console-state-service"), Duration.ofSeconds(5));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static final String WFRN = "wfrn:wf:compute:global:123456789012:vm/vm-01";

    private static String resource(String state, long version) {
        return "{\"wfrn\":\"" + WFRN + "\",\"accountId\":\"123456789012\",\"region\":\"global\",\"type\":\"vm\",\"service\":\"compute\","
                + "\"state\":\"" + state + "\",\"version\":" + version + ",\"registryId\":\"vm-01\",\"providerId\":\"vm-1006\","
                + "\"attributes\":{\"cpu\":2},\"tags\":{},\"createdAt\":\"2026-10-23T10:00:00Z\",\"updatedAt\":\"2026-10-23T11:00:00Z\"}";
    }

    @Test
    void theInventoryIsReadOldestChangeFirstAsTheService() {
        answers.put("GET /v1/resources", "{\"items\":[" + resource("active", 4) + "],\"next\":\"c2\"}");
        Page<StateResource> page = client.listResources("123456789012", Instant.parse("2026-10-23T09:00:00Z"), "c1", 200).block();
        assertThat(page.items()).extracting(StateResource::wfrn).containsExactly(WFRN);
        assertThat(page.items().get(0).registryId()).isEqualTo("vm-01");
        assertThat(page.next()).isEqualTo("c2");
        Seen request = seen.get(0);
        assertThat(request.query()).contains("accountId=123456789012", "sort=updatedAt", "limit=200", "updatedAfter=2026-10-23T09:00:00Z", "after=c1");
        assertThat(request.headers()).containsEntry("authorization", "Bearer svc-token");
    }

    @Test
    void anAnswerThatStallsMidBodyEndsAtTheTimeout() throws IOException {
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        // the headers and the start of the body arrive at once, then nothing
        HttpServer stalling = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stalling.setExecutor(pool);
        stalling.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("{\"items\":[".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        stalling.start();
        try {
            HttpStateService impatient = new HttpStateService(WebClient.builder(), "http://127.0.0.1:" + stalling.getAddress().getPort(),
                    (request, next) -> next.exchange(request), Duration.ofMillis(300));
            long started = System.nanoTime();
            Throwable failure = catchThrowable(() -> impatient.listResources("123456789012", null, null, 10).block(Duration.ofSeconds(8)));
            assertThat(failure).isNotNull();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
            stalling.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void aTombstoneIsADeletedResource() {
        statuses.put("GET /v1/wfrn/" + WFRN, new int[] {410});
        answers.put("GET /v1/wfrn/" + WFRN, "{\"error\":\"resource.deleted\",\"wfrn\":\"" + WFRN
                + "\",\"deletedAt\":\"2026-10-23T11:30:00Z\",\"tombstoneUntil\":\"2027-01-21T11:30:00Z\",\"lastVersion\":7}");
        StateResource gone = client.getResource(WFRN).block();
        assertThat(gone.deleted()).isTrue();
        assertThat(gone.version()).isEqualTo(7);
        assertThat(seen.get(0).path()).isEqualTo("/v1/wfrn/" + WFRN);
    }

    @Test
    void aRefusalCarriesTheStatusErrorAndDetail() {
        String path = "/v1/wfrn/" + WFRN + ":record-drift";
        statuses.put("POST " + path, new int[] {409});
        answers.put("POST " + path, "{\"error\":\"drift.open\",\"detail\":{\"findingId\":\"fnd-other\"}}");
        assertThatThrownBy(() -> client.recordDrift(WFRN, "fnd-1", com.iaf.console.drift.drift.domain.Severity.WARNING, List.of(), "console-drift-service")
                .block())
                .isInstanceOfSatisfying(StateServicePort.StateError.class, e -> {
                    assertThat(e.status()).isEqualTo(409);
                    assertThat(e.error()).isEqualTo("drift.open");
                    assertThat(e.detailText("findingId")).isEqualTo("fnd-other");
                });
    }

    @Test
    void commandsSendTheContractsBodies() throws IOException {
        answers.put("POST /v1/wfrn/" + WFRN + ":resolve-drift", resource("active", 5));
        client.resolveDrift(WFRN, "fnd-1", Resolution.ACCEPTED, "dana").block();
        JsonNode resolve = Json.MAPPER.readTree(seen.get(0).body());
        assertThat(resolve.path("findingId").asText()).isEqualTo("fnd-1");
        assertThat(resolve.path("resolution").asText()).isEqualTo("accepted");

        answers.put("POST /v1/resources:import", resource("active", 1));
        client.importResource(new StateServicePort.ImportCommand("vm", "global", WFRN, "vm-1042", "legacy-01", Json.object(), "dana", "req-1")).block();
        Seen imported = seen.get(1);
        JsonNode body = Json.MAPPER.readTree(imported.body());
        assertThat(body.path("source").asText()).isEqualTo("discover");
        assertThat(body.path("registryId").asText()).isEqualTo("vm-1042");
        assertThat(body.path("providerId").asText()).isEqualTo("vm-1042");
        assertThat(imported.headers()).containsEntry("idempotency-key", "drift-import:req-1").containsEntry("x-console-request-id", "req-1");
    }
}
