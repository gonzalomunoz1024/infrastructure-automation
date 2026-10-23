package com.iaf.console.bff.assistant.ports.outbound;

import com.iaf.console.bff.assistant.domain.Conversation;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * Where conversations live, each the scope's principal's own in the account and region it started
 * in: a conversation of another person, account or region is never found. A conversation is
 * forgotten at its {@code expiresAt}.
 */
public interface ConversationStore {

    /** One conversation; empty when the scope's principal, account and region hold none of that id, or it has expired. */
    Mono<Conversation> find(AccountScope scope, String conversationId);

    /** Stores a new conversation of the scope's principal. */
    Mono<Conversation> insert(AccountScope scope, Conversation conversation);

    /** Replaces a conversation that is still at {@code expectedVersion}; empty when it moved on or is gone. */
    Mono<Conversation> replace(AccountScope scope, Conversation conversation, long expectedVersion);
}
