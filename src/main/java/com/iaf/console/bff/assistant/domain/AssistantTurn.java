package com.iaf.console.bff.assistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One turn of a conversation, the contract's {@code AssistantTurn}: what the person asked, or the
 * assistant's answer, which is {@code thinking} until it is {@code answered} (text, proposals, an
 * explanation) or {@code failed} (an error with one of the three codes). {@code at} is when the
 * turn reached its status.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssistantTurn(
        String turnId,
        String role,
        Instant at,
        String status,
        String text,
        List<AssistantProposal> proposals,
        ResourceExplanation explanation,
        TurnError error) {

    public static final String PERSON = "person";
    public static final String ASSISTANT = "assistant";
    public static final String THINKING = "thinking";
    public static final String ANSWERED = "answered";
    public static final String FAILED = "failed";
    public static final String ID_PREFIX = "turn_";

    public AssistantTurn {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(at, "at");
        proposals = proposals == null || proposals.isEmpty() ? null : List.copyOf(proposals);
    }

    /** Why a turn failed. */
    public record TurnError(String code, String message) {
    }

    public static AssistantTurn person(String turnId, Instant at, String text) {
        return new AssistantTurn(turnId, PERSON, at, null, text, null, null, null);
    }

    public static AssistantTurn thinking(String turnId, Instant at) {
        return new AssistantTurn(turnId, ASSISTANT, at, THINKING, null, null, null, null);
    }

    public static AssistantTurn answered(String turnId, Instant at, String text, List<AssistantProposal> proposals, ResourceExplanation explanation) {
        return new AssistantTurn(turnId, ASSISTANT, at, ANSWERED, text, proposals, explanation, null);
    }

    public static AssistantTurn failed(String turnId, Instant at, String code, String message) {
        return new AssistantTurn(turnId, ASSISTANT, at, FAILED, null, null, null, new TurnError(code, message));
    }

    @JsonIgnore
    public boolean isThinking() {
        return ASSISTANT.equals(role) && THINKING.equals(status);
    }

    @JsonIgnore
    public boolean isAssistant() {
        return ASSISTANT.equals(role);
    }
}
