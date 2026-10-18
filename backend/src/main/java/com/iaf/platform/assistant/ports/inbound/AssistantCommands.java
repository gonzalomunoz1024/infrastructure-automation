package com.iaf.platform.assistant.ports.inbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.platform.assistant.domain.AssistantEvent;
import com.iaf.platform.assistant.domain.ProviderDraft;
import com.iaf.platform.assistant.ports.outbound.AssistantPort.PlaybookSync;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Write side of the builder: sessions, messages, and the one-shot drafts the wizard and the MCP tool use. */
public interface AssistantCommands {

    /** {@code mode} is create or edit; {@code workflow} and {@code draftYaml} apply to edit. Returns the session id. */
    Mono<String> startSession(String mode, String workflow, String draftYaml);

    /** Sends a message and streams the agent's events until done. */
    Flux<AssistantEvent> send(String sessionId, String text);

    /** One shot for the {@code draft_workflow} MCP tool: the final draft payload (structured output) of a fresh session. */
    Mono<JsonNode> draftWorkflow(String description);

    /** "Ask Devin" in the Add provider wizard. */
    Mono<ProviderDraft> draftProvider(String description);

    /** Admin: create or update the agent's playbooks from {@code devin/*.md} (403 for anyone else). */
    Mono<List<PlaybookSync>> syncPlaybooks();
}
