package com.iaf.platform.assistant.adapters.inbound;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.application.AssistantCommandHandler;
import com.iaf.platform.assistant.application.AssistantQueryHandler;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.AssistantSession;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.inbound.AssistantCommands;
import com.iaf.platform.assistant.ports.inbound.AssistantQueries;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.PlaybookSync;
import com.iaf.platform.common.domain.NotFoundException;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The natural-language builder's HTTP surface: {@code POST /assistant/sessions},
 * {@code POST /assistant/sessions/{id}/messages} (an SSE stream whose event name is the event type
 * and whose data is the payload), {@code GET /assistant/sessions/{id}} and the admin's
 * {@code POST /assistant/playbooks:sync}.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/assistant")
public class RestControllerAssistantAdapter implements AssistantCommands, AssistantQueries {

    private final AssistantCommandHandler commands;
    private final AssistantQueryHandler queries;

    public record StartSession(String mode, String workflow, String draftYaml) {
    }

    public record Message(String text) {
    }

    public record Description(String description) {
    }

    /** What {@code POST /assistant/playbooks:sync} returns. */
    public record PlaybooksSynced(List<PlaybookSync> playbooks) {
    }

    /** What {@code GET /assistant/sessions/{id}} returns. */
    public record SessionView(String sessionId, String mode, String workflow, String status, JsonNode draft, List<AssistantSession.Turn> history,
            Instant createdAt, Instant updatedAt) {
        static SessionView of(AssistantSession s) {
            return new SessionView(s.id(), s.mode(), s.workflow(), s.status(), s.draft(), s.history() == null ? List.of() : s.history(), s.createdAt(), s.updatedAt());
        }
    }

    // ---------------------------------------------------------------- HTTP

    @PostMapping("/sessions")
    public Mono<Map<String, String>> start(@RequestBody(required = false) StartSession body) {
        StartSession b = body == null ? new StartSession(null, null, null) : body;
        return startSession(b.mode(), b.workflow(), b.draftYaml()).map(id -> Map.of("sessionId", id));
    }

    @PostMapping(value = "/sessions/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<JsonNode>> messages(@PathVariable String id, @RequestBody Message body) {
        AtomicLong seq = new AtomicLong();
        return send(id, body == null ? null : body.text())
                .map(e -> ServerSentEvent.<JsonNode>builder(e.data()).id(Long.toString(seq.incrementAndGet())).event(e.type()).build());
    }

    @GetMapping("/sessions/{id}")
    public Mono<SessionView> get(@PathVariable String id) {
        return session(id).switchIfEmpty(Mono.error(() -> new NotFoundException("Assistant session", id))).map(SessionView::of);
    }

    @PostMapping("/drafts")
    public Mono<JsonNode> draft(@RequestBody Description body) {
        return draftWorkflow(body == null ? null : body.description());
    }

    @PostMapping("/playbooks:sync")
    public Mono<PlaybooksSynced> sync() {
        return syncPlaybooks().map(PlaybooksSynced::new);
    }

    // ---------------------------------------------------------------- the ports

    @Override
    public Mono<String> startSession(String mode, String workflow, String draftYaml) {
        return commands.startSession(mode, workflow, draftYaml);
    }

    @Override
    public Flux<AssistantEvent> send(String sessionId, String text) {
        return commands.send(sessionId, text);
    }

    @Override
    public Mono<JsonNode> draftWorkflow(String description) {
        return commands.draftWorkflow(description);
    }

    @Override
    public Mono<ProviderDraft> draftProvider(String description) {
        return commands.draftProvider(description);
    }

    @Override
    public Mono<List<PlaybookSync>> syncPlaybooks() {
        return commands.syncPlaybooks();
    }

    @Override
    public Mono<AssistantSession> session(String id) {
        return queries.session(id);
    }
}
