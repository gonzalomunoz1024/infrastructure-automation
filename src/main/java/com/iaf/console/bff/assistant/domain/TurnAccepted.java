package com.iaf.console.bff.assistant.domain;

/** The 202 of a turn: the conversation it belongs to and the assistant turn that will carry the answer. */
public record TurnAccepted(String conversationId, String turnId) {
}
