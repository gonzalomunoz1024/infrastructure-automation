package com.iaf.console.bff.assistant.ports.outbound;

import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The assistant behind the console copilot (hub decision 27): given everything the BFF gathered
 * for one turn, an answer in words and proposals. The firm's Devin API is the first adapter; a
 * faster assistant later is another adapter on this port. An adapter gets no session, token or key
 * of the person and never calls the console; it errors with an {@code AssistantException}
 * ({@code assistant.unavailable}, {@code assistant.timeout}, {@code assistant.refused}), and any
 * other error reads as unavailable. The BFF reviews every answer before anyone sees it.
 */
public interface AssistantPort {

    /** The answer to one turn of the scope's principal, in the scope's account and region. */
    Mono<AssistantAnswer> answer(AccountScope scope, AssistantRequest request);
}
