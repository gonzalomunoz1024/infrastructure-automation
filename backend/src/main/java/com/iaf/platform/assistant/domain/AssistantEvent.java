package com.iaf.platform.assistant.domain;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One event of a builder session, streamed to the UI as SSE. {@code type} is status, draft,
 * message, validation or done; {@code data} is the payload the API doc describes (a draft
 * carries the structured output of devin/workflow-draft.schema.json).
 */
public record AssistantEvent(String type, Instant at, JsonNode data) {

    public static final String STATUS = "status";
    public static final String DRAFT = "draft";
    public static final String MESSAGE = "message";
    public static final String VALIDATION = "validation";
    public static final String DONE = "done";
}
