package com.iaf.console.bff.drift.adapters.outbound.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.drift.application.DriftProperties;
import com.iaf.console.bff.drift.domain.AlarmInput;
import com.iaf.console.bff.drift.domain.DriftCaller;
import com.iaf.console.bff.drift.domain.DriftCommands;
import com.iaf.console.bff.drift.domain.DriftConflictException;
import com.iaf.console.bff.drift.domain.DriftGatewayException;
import com.iaf.console.bff.drift.domain.DriftQueries;
import com.iaf.console.bff.drift.domain.DriftResults;
import com.iaf.console.bff.drift.domain.Finding;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

/**
 * The drift service over HTTP against a JDK server that answers what each case needs: the reads'
 * account, region and strictly encoded values, the writes' headers and bodies, the two tokens (the
 * person's and the console's own), and every refusal turned into the kit's exception, a 409 with
 * its code and a 502 with the drift service's correlation id.
 */
class HttpDriftPortTest {

    private static final String COMPUTE = "123456789012";
    private static final String WFRN = "wfrn:wf:compute:global:" + COMPUTE + ":vm/web-01";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AccountScope SCOPE = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private static final DriftCaller GONZALO = DriftCaller.person("user_gonzalo", List.of("owners"), List.of("account-admin"));

    /** One request as the server saw it. */
    private record Seen(String method, String rawPath, String rawQuery, Headers headers, String body) {

        String header(String name) {
            return headers.getFirst(name);
        }
    }

    /** What the server answers next. */
    private record Answer(int status, String body, Map<String, String> headers) {
    }

    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final ECKey key = OnBehalfOfAssertions.generateKey("console-bff-test");
    private final Trust trust = new Trust(() -> key, new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null,
            null, Duration.ofMinutes(5), Duration.ofMinutes(5), null, null), Clock.systemUTC());
    private HttpServer server;
    private HttpDriftPort port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestHeaders(), body));
            Answer answer;
            synchronized (answers) {
                answer = answers.isEmpty() ? new Answer(200, "{}", Map.of()) : answers.poll();
            }
            answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] out = answer.body().getBytes(StandardCharsets.UTF_8);
            if (out.length > 0) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(answer.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream o = exchange.getResponseBody()) {
                    o.write(out);
                }
            }
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        port = new HttpDriftPort(WebClient.builder(), () -> base, trust, new DriftProperties("", "console-drift-service", Duration.ofSeconds(5)), JSON);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String body) {
        answer(status, body, Map.of());
    }

    private void answer(int status, String body, Map<String, String> headers) {
        synchronized (answers) {
            answers.add(new Answer(status, body, headers));
        }
    }

    private Seen last() {
        return seen.get(seen.size() - 1);
    }

    @Test
    void aReadNamesTheAccountAndRegionAndEncodesEveryValueStrictly() throws Exception {
        answer(200, """
                {"items":[{"findingId":"fnd-1","wfrn":"%s","accountId":"%s","region":"global","type":"vm","status":"open","severity":"warning",
                  "version":3,"fields":[{"path":"/cpu","expected":4,"observed":8}],"openedAt":"2026-10-23T10:00:00Z","somethingNew":true}],
                 "next":"c2"}""".formatted(WFRN, COMPUTE));
        Page<Finding> page = port.listFindings(SCOPE, GONZALO,
                new DriftQueries.Findings("open", null, "vm", "wfrn:wf:compute:global:" + COMPUTE + ":vm/*", null, 25, "c+1&x")).block();

        assertEquals(1, page.items().size());
        assertEquals("fnd-1", page.items().get(0).findingId());
        assertEquals(8, page.items().get(0).fields().get(0).observed().asInt());
        assertEquals(Instant.parse("2026-10-23T10:00:00Z"), page.items().get(0).openedAt());
        assertEquals("c2", page.next());

        Seen request = last();
        assertEquals("GET", request.method());
        assertEquals("/v1/findings", request.rawPath());
        String query = request.rawQuery();
        assertTrue(query.contains("accountId=" + COMPUTE), query);
        assertTrue(query.contains("region=global"), query);
        assertTrue(query.contains("status=open"), query);
        assertTrue(query.contains("limit=25"), query);
        assertTrue(query.contains("after=c%2B1%26x"), "a + and an & are encoded, not read as a space and a new parameter: " + query);
        assertTrue(query.contains("wfrn=wfrn%3Awf%3Acompute%3Aglobal%3A" + COMPUTE + "%3Avm%2F%2A"), query);
        assertFalse(query.contains("severity"), "what is not set is left out: " + query);

        JWTClaimsSet claims = SignedJWT.parse(request.header("Authorization").substring("Bearer ".length())).getJWTClaimsSet();
        assertEquals("user_gonzalo", claims.getSubject());
        assertEquals(COMPUTE, claims.getStringClaim("acct"));
        assertEquals("global", claims.getStringClaim("region"));
        assertTrue(claims.getAudience().contains("console-drift-service"), claims.getAudience().toString());
        assertNull(request.header(HttpDriftPort.ON_BEHALF_OF), "a read carries no assertion");
    }

    @Test
    void theConsoleItselfAsksWithItsOwnToken() throws Exception {
        answer(200, """
                {"accountId":"%s","since":"2026-09-23T00:00:00Z","until":"2026-10-23T00:00:00Z","opened":4,
                 "closed":{"total":1,"reverted":1,"accepted":0,"ignored":0,"vanished":0},"open":3,"bySeverity":{"warning":4}}""".formatted(COMPUTE));
        var stats = port.getFindingStats(SCOPE, DriftCaller.internalPrincipal(), Instant.parse("2026-09-23T00:00:00Z"), null).block();

        assertEquals(4, stats.opened());
        assertEquals(1, stats.closed().reverted());
        assertNull(stats.meanMinutesToClose(), "nothing to average is left out, not zero");
        Seen request = last();
        assertEquals("/v1/findings/stats", request.rawPath());
        assertTrue(request.rawQuery().contains("accountId=" + COMPUTE), request.rawQuery());
        assertTrue(request.rawQuery().contains("since=2026-09-23T00%3A00%3A00Z"), request.rawQuery());
        assertFalse(request.rawQuery().contains("until"), "the drift service's default: now");
        JWTClaimsSet claims = SignedJWT.parse(request.header("Authorization").substring("Bearer ".length())).getJWTClaimsSet();
        assertNull(claims.getStringClaim("acct"), "the console-internal principal has no account of its own");
        assertEquals("console-bff", claims.getSubject());
        assertEquals("console-bff", claims.getStringClaim(Trust.SERVICE_CLAIM));
    }

    @Test
    void aWriteCarriesItsKeyRequestIdAndAssertion() throws Exception {
        answer(202, "{\"findingId\":\"fnd-1\",\"jobId\":\"job-9\"}");
        DriftResults.RevertStarted started = port.revertFinding(SCOPE, GONZALO, "fnd-1", new DriftCommands.Decision("back to plan", "user_gonzalo", null),
                new DriftCommands.Meta("key-1", "req_abc", "the-assertion")).block();

        assertEquals("job-9", started.jobId());
        Seen request = last();
        assertEquals("POST", request.method());
        assertEquals("/v1/findings/fnd-1:revert", request.rawPath());
        assertEquals("key-1", request.header(HttpDriftPort.IDEMPOTENCY_KEY));
        assertEquals("req_abc", request.header(HttpDriftPort.REQUEST_ID));
        assertEquals("the-assertion", request.header(HttpDriftPort.ON_BEHALF_OF));
        JsonNode body = JSON.readTree(request.body());
        assertEquals("back to plan", body.path("reason").asText());
        assertEquals("user_gonzalo", body.path("actor").asText());
        assertFalse(body.has("until"), "what is not set is left out: " + body);

        answer(200, "{\"findingId\":\"fnd-2\",\"status\":\"closed\",\"resolution\":\"ignored\"}");
        port.ignoreFinding(SCOPE, GONZALO, "fnd-2", new DriftCommands.Decision(null, "user_gonzalo", Instant.parse("2026-11-01T00:00:00Z")),
                new DriftCommands.Meta(null, "req_def", null)).block();
        assertEquals("2026-11-01T00:00:00Z", JSON.readTree(last().body()).path("until").asText());
        assertNull(last().header(HttpDriftPort.IDEMPOTENCY_KEY), "no key, no header");
        assertNull(last().header(HttpDriftPort.ON_BEHALF_OF), "an ignore runs nothing on the platform");
    }

    @Test
    void anAlarmsPutSaysWhetherItWasCreatedAndADeleteHasNoBody() throws Exception {
        String alarm = "{\"alarmId\":\"a-1\",\"wfrn\":\"%s\",\"metric\":\"cpuPercent\",\"comparison\":\"above\",\"threshold\":80,\"state\":\"ok\"}"
                .formatted(WFRN);
        AlarmInput input = new AlarmInput(WFRN, null, "cpuPercent", "above", 80.0, null, "user_gonzalo");
        answer(201, alarm);
        assertTrue(port.putAlarm(SCOPE, GONZALO, "a-1", input, new DriftCommands.Meta(null, "req_1", null)).block().created());
        JsonNode body = JSON.readTree(last().body());
        assertEquals(80.0, body.path("threshold").asDouble());
        assertFalse(body.has("name"), body.toString());
        assertEquals("PUT", last().method());
        answer(200, alarm);
        assertFalse(port.putAlarm(SCOPE, GONZALO, "a-1", input, new DriftCommands.Meta(null, "req_2", null)).block().created());

        answer(204, "");
        port.deleteAlarm(SCOPE, GONZALO, "a-1", new DriftCommands.Meta(null, "req_3", null)).block();
        assertEquals("DELETE", last().method());
        assertEquals("/v1/alarms/a-1", last().rawPath());
    }

    @Test
    void refusalsBecomeTheKitsExceptions() {
        answer(400, "{\"error\":\"type is required\",\"problems\":[{\"code\":\"required\",\"field\":\"/type\",\"message\":\"type is required\"}]}");
        InvalidInputException invalid = assertThrows(InvalidInputException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals("required", invalid.problems().get(0).code());
        assertEquals("/type", invalid.problems().get(0).field());
        // the drift service names a field; the BFF's problems point into the body
        answer(400, "{\"error\":\"invalid\",\"problems\":[{\"code\":\"unmanaged.unknownAccount\",\"field\":\"accountId\",\"message\":\"no such account\"},"
                + "{\"code\":\"range\",\"field\":\"desired.cpu\",\"message\":\"too many\"}]}");
        InvalidInputException named = assertThrows(InvalidInputException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals("/accountId", named.problems().get(0).field());
        assertEquals("/desired/cpu", named.problems().get(1).field());

        answer(404, "{\"error\":\"finding fnd-1 not found\"}");
        NotFoundException missing = assertThrows(NotFoundException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals("finding fnd-1 not found", missing.getMessage());

        answer(409, "{\"error\":\"finding.reverting: a revert job is already running\",\"detail\":{\"jobId\":\"job-7\",\"step\":null}}");
        DriftConflictException reverting = assertThrows(DriftConflictException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals("finding.reverting", reverting.code());
        assertEquals("a revert job is already running", reverting.getMessage());
        assertEquals(Map.of("jobId", "job-7"), reverting.detail(), "a null in the detail is dropped");

        answer(409, "{\"error\":\"something clashed\"}");
        DriftConflictException unnamed = assertThrows(DriftConflictException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals(DriftConflictException.UNNAMED, unnamed.code());
        assertEquals("something clashed", unnamed.getMessage());

        answer(429, "{\"error\":\"the observe budget is spent\",\"retryAfter\":3}", Map.of("Retry-After", "7"));
        assertEquals(7, assertThrows(TooManyRequestsException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block()).retryAfterSeconds());
        answer(429, "{\"error\":\"the observe budget is spent\",\"retryAfter\":3}");
        assertEquals(3, assertThrows(TooManyRequestsException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block()).retryAfterSeconds(),
                "without the header, the body's figure");

        answer(502, "{\"error\":\"platform.failed: the platform refused the job\",\"correlationId\":\"plat-123\",\"detail\":{\"status\":503}}");
        DriftGatewayException gateway = assertThrows(DriftGatewayException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals("plat-123", gateway.correlationId());
        assertEquals("platform.failed: the platform refused the job", gateway.getMessage());
        assertEquals(503, ((JsonNode) gateway.detail()).path("status").asInt());

        answer(500, "not json at all");
        WebClientResponseException other = assertThrows(WebClientResponseException.class, () -> port.getFinding(SCOPE, GONZALO, "fnd-1").block());
        assertEquals(500, other.getStatusCode().value());
    }
}
