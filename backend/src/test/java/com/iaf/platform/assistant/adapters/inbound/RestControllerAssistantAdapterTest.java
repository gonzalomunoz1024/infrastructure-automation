package com.iaf.platform.assistant.adapters.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.adapters.outbound.InMemorySessionStore;
import com.iaf.platform.assistant.application.AssistantCommandHandler;
import com.iaf.platform.assistant.application.AssistantQueryHandler;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.Events;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.domain.WorkflowDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort;
import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.GlobalExceptionHandler;
import com.iaf.platform.workflows.ports.inbound.WorkflowQueries;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The HTTP surface: a session id, an SSE stream whose event names are the event types, the session view, 404s. */
class RestControllerAssistantAdapterTest {

    static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    static final AssistantPort AGENT = new AssistantPort() {
        @Override
        public Flux<AssistantEvent> run(AssistantRequest request) {
            WorkflowDraft draft = new WorkflowDraft(WorkflowDraft.READY, "Drafted " + request.text(), "kind: Workflow\n", List.of(), List.of(), List.of(), List.of(),
                    new WorkflowDraft.Validation(true, 1, List.of()), null);
            return Flux.just(Events.status(NOW, "Drafting"), Events.draft(NOW, draft), Events.validation(NOW, List.of()), Events.message(NOW, "Here you go."), Events.done(NOW));
        }

        @Override
        public Mono<ProviderDraft> draftProvider(String description, Identity who) {
            return Mono.just(new ProviderDraft("kind: Provider\n", description, List.of(), List.of()));
        }

        @Override
        public String mode() {
            return "scripted";
        }
    };

    WebTestClient client;

    @BeforeEach
    void setUp() {
        InMemorySessionStore store = new InMemorySessionStore();
        WorkflowQueries workflows = mock(WorkflowQueries.class);
        when(workflows.check(anyString())).thenReturn(Mono.just(List.of()));
        AssistantCommandHandler commands = new AssistantCommandHandler(AGENT, store, workflows, CLOCK, new MockEnvironment().withProperty("server.port", "8080"));
        AssistantQueryHandler queries = new AssistantQueryHandler(store);
        client = WebTestClient.bindToController(new RestControllerAssistantAdapter(commands, queries))
                .controllerAdvice(new GlobalExceptionHandler())
                .configureClient().responseTimeout(Duration.ofSeconds(10)).build();
    }

    String start(String mode) {
        Map<String, String> body = client.post().uri("/api/v1/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", mode))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, String>>() {
                })
                .returnResult().getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("sessionId")).startsWith("asst");
        return body.get("sessionId");
    }

    @Test
    void messagesStreamServerSentEventsNamedAfterTheEventType() {
        String id = start("create");
        List<ServerSentEvent<JsonNode>> events = client.post().uri("/api/v1/assistant/sessions/{id}/messages", id)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of("text", "restart a vm"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(new ParameterizedTypeReference<ServerSentEvent<JsonNode>>() {
                })
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(10));
        assertThat(events).isNotNull();
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly(AssistantEvent.STATUS, AssistantEvent.DRAFT, AssistantEvent.VALIDATION, AssistantEvent.MESSAGE, AssistantEvent.DONE);
        assertThat(events).extracting(ServerSentEvent::id).containsExactly("1", "2", "3", "4", "5");
        assertThat(events.get(1).data().path("summary").asText()).isEqualTo("Drafted restart a vm");
        assertThat(events.get(1).data().path("workflowYaml").asText()).isEqualTo("kind: Workflow\n");
        assertThat(events.get(2).data().path("valid").asBoolean()).isTrue();
        assertThat(events.get(3).data().path("text").asText()).isEqualTo("Here you go.");
        assertThat(events.get(4).data().path("status").asText()).isEqualTo("done");

        client.get().uri("/api/v1/assistant/sessions/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.sessionId").isEqualTo(id)
                .jsonPath("$.mode").isEqualTo("create")
                .jsonPath("$.status").isEqualTo("done")
                .jsonPath("$.draft.summary").isEqualTo("Drafted restart a vm")
                .jsonPath("$.history.length()").isEqualTo(2)
                .jsonPath("$.history[0].role").isEqualTo("user")
                .jsonPath("$.history[1].text").isEqualTo("Here you go.");
    }

    @Test
    void unknownSessionsAre404AndBadModesAre400() {
        client.get().uri("/api/v1/assistant/sessions/asst-missing").exchange().expectStatus().isNotFound();
        client.post().uri("/api/v1/assistant/sessions/asst-missing/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "x"))
                .exchange()
                .expectStatus().isNotFound();
        client.post().uri("/api/v1/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", "review"))
                .exchange()
                .expectStatus().isBadRequest();
        client.post().uri("/api/v1/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", "edit"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void anEditSessionStartsFromTheDraftOnTheCanvas() {
        String yaml = """
                apiVersion: iaf.platform/v1
                kind: Workflow
                metadata:
                  name: x
                  title: X
                  noun: thing
                  verb: poke
                  owner: team-a
                  folder: Things
                  version: 0.1.0
                api:
                  method: POST
                  path: /api/v1/things/{thingId}:poke
                  input:
                    type: object
                    required: [thingId]
                    properties:
                      thingId: { type: string, title: Thing id }
                workflow:
                  do:
                    - poke:
                        name: Poke
                        set: { poked: "{{ $input.thingId }}" }
                """;
        Map<String, String> body = client.post().uri("/api/v1/assistant/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mode", "edit", "draftYaml", yaml))
                .exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, String>>() {
                })
                .returnResult().getResponseBody();
        assertThat(body).isNotNull();
        client.get().uri("/api/v1/assistant/sessions/{id}", body.get("sessionId"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.mode").isEqualTo("edit")
                .jsonPath("$.workflow").isEqualTo("x")
                .jsonPath("$.status").isEqualTo("idle")
                .jsonPath("$.draft.workflowYaml").isEqualTo(yaml);
    }

    @Test
    void oneShotDraftsReturnTheFinalPayload() {
        client.post().uri("/api/v1/assistant/drafts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("description", "restart a vm"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ready")
                .jsonPath("$.summary").isEqualTo("Drafted restart a vm");
    }
}
