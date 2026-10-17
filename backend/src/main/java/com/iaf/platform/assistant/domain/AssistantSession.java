package com.iaf.platform.assistant.domain;

import java.time.Instant;
import java.util.ArrayList;
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

    public static final String CREATE = "create";
    public static final String EDIT = "edit";

    public static final String IDLE = "idle";
    public static final String WORKING = "working";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    /** {@code role} is user or assistant. */
    public record Turn(String role, String text, Instant at) {
    }

    public AssistantSession withStatus(String s, Instant at) {
        return new AssistantSession(id, mode, workflow, requester, s, draft, history, externalId, createdAt, at);
    }

    public AssistantSession withDraft(JsonNode d, Instant at) {
        return new AssistantSession(id, mode, workflow, requester, status, d, history, externalId, createdAt, at);
    }

    public AssistantSession withTurn(String role, String text, Instant at) {
        List<Turn> turns = new ArrayList<>(history == null ? List.of() : history);
        turns.add(new Turn(role, text, at));
        return new AssistantSession(id, mode, workflow, requester, status, draft, List.copyOf(turns), externalId, createdAt, at);
    }

    public AssistantSession withExternalId(String external, Instant at) {
        return new AssistantSession(id, mode, workflow, requester, status, draft, history, external, createdAt, at);
    }

    /** The YAML of the latest draft, or null before any draft exists. */
    public String currentYaml() {
        if (draft == null || !draft.hasNonNull("workflowYaml")) {
            return null;
        }
        String yaml = draft.get("workflowYaml").asText();
        return yaml.isBlank() ? null : yaml;
    }

    public boolean isEdit() {
        return EDIT.equals(mode);
    }
}
