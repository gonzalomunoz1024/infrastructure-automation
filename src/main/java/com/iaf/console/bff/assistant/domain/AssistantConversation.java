package com.iaf.console.bff.assistant.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A conversation as the console reads it, the contract's {@code AssistantConversation}: its turns,
 * oldest first, and {@code thinking} while its last turn is under way, then {@code ready} or
 * {@code failed} as that turn ended.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantConversation(String conversationId, String status, Instant startedAt, List<AssistantTurn> turns) {

    public static final String THINKING = "thinking";
    public static final String READY = "ready";
    public static final String FAILED = "failed";

    public AssistantConversation {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
