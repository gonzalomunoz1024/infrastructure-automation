package com.iaf.console.bff.assistant.adapters.inbound;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.assistant.application.Assistant;
import com.iaf.console.bff.assistant.domain.AssistantAsk;
import com.iaf.console.bff.assistant.domain.AssistantConversation;
import com.iaf.console.bff.assistant.domain.TurnAccepted;
import com.iaf.console.bff.session.application.CurrentSession;

import reactor.core.publisher.Mono;

/**
 * {@code /console/v1/assistant}: a turn of the copilot ({@code POST /turns}, 202 with the
 * conversation and the turn that will carry the answer) and the conversation the console polls
 * while the turn is {@code thinking}. The write needs the CSRF token, as every write under
 * {@code /console/} does.
 */
@RestController
public class AssistantController {

    private final Assistant assistant;

    public AssistantController(Assistant assistant) {
        this.assistant = assistant;
    }

    @PostMapping(path = "/console/v1/assistant/turns", consumes = "application/json", produces = "application/json")
    public Mono<ResponseEntity<TurnAccepted>> ask(@RequestBody(required = false) AssistantAsk body, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> assistant.ask(session, body))
                .map(accepted -> ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted));
    }

    @GetMapping(path = "/console/v1/assistant/conversations/{conversationId}", produces = "application/json")
    public Mono<AssistantConversation> conversation(@PathVariable String conversationId, ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> assistant.conversation(session, conversationId));
    }
}
