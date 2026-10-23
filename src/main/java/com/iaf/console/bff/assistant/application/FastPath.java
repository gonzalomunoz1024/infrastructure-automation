package com.iaf.console.bff.assistant.application;

import com.iaf.console.bff.assistant.domain.AssistantAnswer;
import com.iaf.console.bff.assistant.domain.AssistantRequest;
import com.iaf.console.bff.session.domain.Session;

import reactor.core.publisher.Mono;

/**
 * A quicker way to an answer than the assistant, tried first on every turn: the search indexer's
 * reading of plain English as the list's own filter ({@code search:interpret}), for instance, can
 * answer "show the failed VMs in prod" with a filter proposal in milliseconds. An answer here is
 * reviewed like the assistant's and the assistant is not asked; empty sends the turn on to the
 * assistant, and so does an error, which is logged. Register a bean of this type to plug one in;
 * without one, {@link #NONE} answers empty and every turn goes to the assistant.
 */
@FunctionalInterface
public interface FastPath {

    /** No fast path: every turn goes to the assistant. */
    FastPath NONE = (session, request) -> Mono.empty();

    /** An answer for the turn, read with the person's own session; empty to ask the assistant. */
    Mono<AssistantAnswer> answer(Session session, AssistantRequest request);
}
