package com.iaf.platform.assistant.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/** A natural-language builder session: who asked, in which mode, the latest draft and the exchange so far. */
public record AssistantSession(
        String id,
        String mode,
        String workflow,
        String requester,
        String status,
        JsonNode draft,
        List<Turn> history,
        String externalId,
        Instant createdAt,
        Instant updatedAt) {

    /** {@code role} is user or assistant. */
    public record Turn(String role, String text, Instant at) {
    }
}
