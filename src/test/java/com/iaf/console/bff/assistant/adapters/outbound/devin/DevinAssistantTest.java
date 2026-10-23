package com.iaf.console.bff.assistant.adapters.outbound.devin;

import static com.iaf.console.bff.assistant.AssistantFixtures.COMPUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.assistant.AssistantFixtures;
import com.iaf.console.bff.assistant.application.AssistantProperties;
import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantException;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;

/**
 * The Devin adapter against a stand-in of Devin's v1 API on a local port: a session that
 * finishes is the answer, one that never does a timeout (and is ended), an HTTP failure
 * {@code assistant.unavailable}, an answer the console cannot read {@code assistant.refused}.
 */
class DevinAssistantTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final AccountScope SCOPE = AccountScope.of(COMPUTE, "global", "user_gonzalo");
    private static final String ANSWER = """
            {"text": "Here are the failed VMs.", "proposals": [{"kind": "filter", "label": "Failed VMs", "type": "vm", "filter": {"state": "failed"}}]}""";

    /** The key for this run only. */
    private final String key = "devin-test-" + UUID.randomUUID();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private volatile String created;
    private volatile String authorization;
    private volatile int createStatus = 200;
    private volatile int singularStatus = 200;
    /** The nth read of the session (from 1), as Devin answers it. */
    private volatile IntFunction<Stub> session = n -> new Stub(200, "{\"session_id\": \"devin-1\", \"status_enum\": \"working\"}");
    private DisposableServer server;

    record Stub(int status, String body) {
    }

    @BeforeEach
    void startDevin() {
        server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes
                .post("/v1/sessions", (req, res) -> req.receive().aggregate().asString().defaultIfEmpty("").flatMap(body -> {
                    calls.add("POST /v1/sessions");
                    created = body;
                    authorization = req.requestHeaders().get("Authorization");
                    return send(res, new Stub(createStatus, createStatus == 200
                            ? "{\"session_id\": \"devin-1\", \"url\": \"https://app.devin.test/sessions/1\", \"is_new_session\": true}"
                            : "{\"detail\": \"no\"}"));
                }))
                .get("/v1/session/{id}", (req, res) -> {
                    calls.add("GET /v1/session/" + req.param("id"));
                    return singularStatus == 404 ? send(res, new Stub(404, "{\"detail\": \"Not Found\"}")) : read(res);
                })
                .get("/v1/sessions/{id}", (req, res) -> {
                    calls.add("GET /v1/sessions/" + req.param("id"));
                    return read(res);
                })
                .delete("/v1/sessions/{id}", (req, res) -> {
                    calls.add("DELETE /v1/sessions/" + req.param("id"));
                    return send(res, new Stub(200, "{\"detail\": \"Session terminated successfully\"}"));
                }))
                .bindNow();
    }

    @AfterEach
    void stopDevin() {
        server.disposeNow();
    }

    @Test
    void aFinishedSessionIsTheAnswer() throws Exception {
        session = n -> n < 2 ? working() : new Stub(200, "{\"session_id\": \"devin-1\", \"status_enum\": \"finished\", \"structured_output\": " + ANSWER + "}");
        AssistantAnswer answer = devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("show the failed vms")).block();
        assertEquals("Here are the failed VMs.", answer.text());
        assertEquals("filter", answer.proposals().get(0).kind());
        assertEquals("failed", answer.proposals().get(0).filter().state());
        assertEquals(List.of("POST /v1/sessions", "GET /v1/session/devin-1", "GET /v1/session/devin-1"), calls, "a finished session needs no ending");

        assertEquals("Bearer " + key, authorization);
        JsonNode body = JSON.readTree(created);
        String prompt = body.path("prompt").asText();
        assertTrue(prompt.contains("\"message\":\"show the failed vms\""), "the request as JSON: " + prompt);
        assertTrue(prompt.contains("\"turnId\":\"turn_1\""), prompt);
        assertTrue(prompt.contains("\"title\": \"Console copilot answer\""), "the answer's schema is in the prompt");
        assertFalse(prompt.contains("{{"), "every placeholder is filled");
        assertFalse(prompt.contains(key), "the key is never in the prompt");
        assertTrue(body.path("secret_ids").isArray() && body.path("secret_ids").isEmpty(), "no organization secret");
        assertTrue(body.path("knowledge_ids").isArray() && body.path("knowledge_ids").isEmpty(), "no knowledge the person may not read");
        assertTrue(body.path("unlisted").asBoolean());
        assertEquals("Console copilot answer", body.path("structured_output_schema").path("title").asText());
        assertEquals("[\"console-copilot\",\"ask\"]", body.path("tags").toString());
        assertEquals("Console copilot turn_1", body.path("title").asText());
        assertFalse(body.has("max_acu_limit"), "Devin's own limit unless one is set");
    }

    @Test
    void aSessionThatNeverFinishesTimesOutAndIsEnded() throws Exception {
        AssistantException e = assertThrows(AssistantException.class, () -> devin(Duration.ofMillis(400), 3).answer(SCOPE, request("hello")).block());
        assertEquals(AssistantException.TIMEOUT, e.code());
        assertEquals("the assistant did not answer within 400 milliseconds", e.getMessage());
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> calls.contains("DELETE /v1/sessions/devin-1"));
        assertEquals(3, JSON.readTree(created).path("max_acu_limit").asInt(), "a limit that is set is sent");
    }

    @Test
    void anHttpFailureIsUnavailable() {
        createStatus = 500;
        AssistantException failed = assertThrows(AssistantException.class, () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block());
        assertEquals(AssistantException.UNAVAILABLE, failed.code());
        assertEquals("the assistant answered 500 when the copilot tried to start a session", failed.getMessage());

        createStatus = 401;
        assertEquals(AssistantException.UNAVAILABLE, assertThrows(AssistantException.class,
                () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block()).code());

        createStatus = 200;
        session = n -> new Stub(503, "upstream down");
        AssistantException reads503 = assertThrows(AssistantException.class, () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block());
        assertEquals(AssistantException.UNAVAILABLE, reads503.code());
        assertEquals(DevinAssistant.READ_FAILURES, reads.get(), "a read that fails is tried again, three times in a row at most");
    }

    @Test
    void aReadThatFailsOnceIsReadAgain() {
        session = n -> switch (n) {
            case 1 -> new Stub(502, "bad gateway");
            case 2 -> working();
            default -> new Stub(200, "{\"status_enum\": \"finished\", \"structured_output\": " + ANSWER + "}");
        };
        assertEquals("Here are the failed VMs.", devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block().text());
    }

    @Test
    void anUnreachableDevinIsUnavailable() {
        DevinProperties nowhere = new DevinProperties("http://127.0.0.1:1", key, Duration.ofMillis(20), Duration.ofSeconds(2), 0);
        DevinAssistant devin = new DevinAssistant(WebClient.builder(), nowhere, properties(Duration.ofSeconds(5)), JSON);
        AssistantException e = assertThrows(AssistantException.class, () -> devin.answer(SCOPE, request("hi")).block());
        assertEquals(AssistantException.UNAVAILABLE, e.code());
        assertEquals("the assistant could not be reached to start a session", e.getMessage());
    }

    @Test
    void anAnswerTheConsoleCannotReadIsRefused() {
        for (String output : List.of("\"not json at all\"", "{}", "{\"answer\": \"hi\"}", "[1, 2]", "null")) {
            reads.set(0);
            session = n -> new Stub(200, "{\"status_enum\": \"finished\", \"structured_output\": " + output + ", \"messages\": [{\"type\": \"devin_message\", "
                    + "\"event_id\": \"e1\", \"message\": \"All done!\", \"timestamp\": \"2026-10-23T10:00:00Z\"}]}");
            AssistantException e = assertThrows(AssistantException.class, () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block(), output);
            assertEquals(AssistantException.REFUSED, e.code(), output);
            assertEquals("the assistant finished without an answer the console can read", e.getMessage());
        }
    }

    @Test
    void anAnswerInTheLastMessageIsReadWhenThereIsNoStructuredOutput() throws Exception {
        String message = JSON.writeValueAsString("Here is my answer: " + ANSWER);
        session = n -> new Stub(200, "{\"status_enum\": \"finished\", \"structured_output\": null, \"messages\": ["
                + "{\"type\": \"user_message\", \"event_id\": \"e0\", \"message\": \"{\\\"text\\\": \\\"not this one\\\"}\", \"timestamp\": \"2026-10-23T10:00:00Z\"},"
                + "{\"type\": \"devin_message\", \"event_id\": \"e1\", \"message\": " + message + ", \"timestamp\": \"2026-10-23T10:00:01Z\"}]}");
        assertEquals("Here are the failed VMs.", devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block().text());
    }

    @Test
    void aRefusalIsTheAssistants() {
        session = n -> new Stub(200, "{\"status_enum\": \"finished\", \"structured_output\": {\"refused\": true, \"reason\": \"Not about the console.\"}}");
        AssistantAnswer answer = devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("write me a poem")).block();
        assertTrue(answer.refused());
        assertEquals("Not about the console.", answer.reason());
    }

    @Test
    void theDocumentedPathIsReadOnceTheFirstAnswers404() {
        singularStatus = 404;
        session = n -> n < 2 ? working() : new Stub(200, "{\"status_enum\": \"finished\", \"structured_output\": " + ANSWER + "}");
        DevinAssistant devin = devin(Duration.ofSeconds(5), 0);
        assertEquals("Here are the failed VMs.", devin.answer(SCOPE, request("hi")).block().text());
        assertEquals(List.of("POST /v1/sessions", "GET /v1/session/devin-1", "GET /v1/sessions/devin-1", "GET /v1/sessions/devin-1"), calls);
    }

    @Test
    void aBlockedSessionAnsweredOrAskedAndIsEnded() {
        session = n -> new Stub(200, "{\"status_enum\": \"blocked\", \"structured_output\": " + ANSWER + "}");
        assertEquals("Here are the failed VMs.", devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block().text());
        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> calls.contains("DELETE /v1/sessions/devin-1"));

        session = n -> new Stub(200, "{\"status_enum\": \"blocked\", \"structured_output\": null}");
        AssistantException asked = assertThrows(AssistantException.class, () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block());
        assertEquals(AssistantException.REFUSED, asked.code());
        assertEquals("the assistant stopped to ask a question instead of answering", asked.getMessage());
    }

    @Test
    void anExpiredOrStoppedSessionEndsTheTurn() {
        session = n -> new Stub(200, "{\"status_enum\": \"expired\"}");
        assertEquals(AssistantException.TIMEOUT, assertThrows(AssistantException.class,
                () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block()).code());
        session = n -> new Stub(200, "{\"status_enum\": \"suspend_requested\"}");
        assertEquals(AssistantException.UNAVAILABLE, assertThrows(AssistantException.class,
                () -> devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block()).code());
        session = n -> new Stub(200, "{\"status\": \"Finished\", \"structured_output\": " + ANSWER + "}");
        assertEquals("Here are the failed VMs.", devin(Duration.ofSeconds(5), 0).answer(SCOPE, request("hi")).block().text(),
                "without status_enum the status says it");
    }

    @Test
    void withoutAKeyDevinIsNeverCalled() {
        DevinProperties keyless = new DevinProperties("http://127.0.0.1:" + server.port(), "", Duration.ofMillis(20), Duration.ofSeconds(2), 0);
        DevinAssistant devin = new DevinAssistant(WebClient.builder(), keyless, properties(Duration.ofSeconds(5)), JSON);
        AssistantException e = assertThrows(AssistantException.class, () -> devin.answer(SCOPE, request("hi")).block());
        assertEquals(AssistantException.UNAVAILABLE, e.code());
        assertTrue(calls.isEmpty());
        assertFalse(keyless.toString().contains("devin-test"), keyless.toString());
        assertFalse(new DevinProperties(null, key, null, null, 0).toString().contains(key), "the key is never in the settings' text");
    }

    // ---- helpers

    private DevinAssistant devin(Duration turnTimeout, int maxAcu) {
        DevinProperties properties = new DevinProperties("http://127.0.0.1:" + server.port() + "/", key, Duration.ofMillis(20), Duration.ofSeconds(2), maxAcu);
        return new DevinAssistant(WebClient.builder(), properties, properties(turnTimeout), JSON);
    }

    private static AssistantProperties properties(Duration turnTimeout) {
        return new AssistantProperties("devin", 10, Duration.ofHours(24), turnTimeout, Duration.ofSeconds(1));
    }

    private static AssistantRequest request(String message) {
        return AssistantFixtures.ask(message, List.of(AssistantFixtures.vm(true, true, true)));
    }

    private static Stub working() {
        return new Stub(200, "{\"session_id\": \"devin-1\", \"status\": \"running\", \"status_enum\": \"working\", \"structured_output\": null}");
    }

    private Mono<Void> read(HttpServerResponse res) {
        return send(res, session.apply(reads.incrementAndGet()));
    }

    private static Mono<Void> send(HttpServerResponse res, Stub stub) {
        return res.status(stub.status()).header("Content-Type", "application/json").sendString(Mono.just(stub.body())).then();
    }
}
