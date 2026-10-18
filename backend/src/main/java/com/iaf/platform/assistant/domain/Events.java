package com.iaf.platform.assistant.domain;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.platform.common.domain.Problem;

/** The payload shapes of the builder's events, so every adapter speaks the same SSE dialect. */
public final class Events {

    private static final ObjectMapper MAPPER = new ObjectMapper().setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));

    private Events() {
    }

    /** {@code status}: {@code { status: "Reading the registry" }}, with the agent's own session id when one exists. */
    public static AssistantEvent status(Instant at, String text, String externalId) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("status", text);
        if (externalId != null) {
            data.put("externalId", externalId);
        }
        return new AssistantEvent(AssistantEvent.STATUS, at, data);
    }

    public static AssistantEvent status(Instant at, String text) {
        return status(at, text, null);
    }

    /**
     * {@code status} saying the agent's own session is over: its {@code externalId} is empty, so
     * the builder session forgets it and the next message starts a new one.
     */
    public static AssistantEvent sessionEnded(Instant at, String text) {
        return status(at, text, "");
    }

    /** {@code message}: {@code { role: "assistant", text }}. */
    public static AssistantEvent message(Instant at, String text) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("role", "assistant");
        data.put("text", text);
        return new AssistantEvent(AssistantEvent.MESSAGE, at, data);
    }

    /** {@code draft}: the structured output of devin/workflow-draft.schema.json. */
    public static AssistantEvent draft(Instant at, WorkflowDraft draft) {
        return new AssistantEvent(AssistantEvent.DRAFT, at, draft.toJson());
    }

    public static AssistantEvent draft(Instant at, JsonNode payload) {
        return new AssistantEvent(AssistantEvent.DRAFT, at, payload);
    }

    /** {@code validation}: {@code { valid, problems: Problem[] }}. */
    public static AssistantEvent validation(Instant at, List<Problem> problems) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("valid", problems.stream().noneMatch(Problem::isError));
        ArrayNode arr = data.putArray("problems");
        problems.forEach(p -> arr.add(MAPPER.valueToTree(p)));
        return new AssistantEvent(AssistantEvent.VALIDATION, at, data);
    }

    /** {@code done}: {@code { status: "done" | "failed", error? }}. */
    public static AssistantEvent done(Instant at) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("status", AssistantSession.DONE);
        return new AssistantEvent(AssistantEvent.DONE, at, data);
    }

    public static AssistantEvent failed(Instant at, String error) {
        ObjectNode data = MAPPER.createObjectNode();
        data.put("status", AssistantSession.FAILED);
        if (error != null) {
            data.put("error", error);
        }
        return new AssistantEvent(AssistantEvent.DONE, at, data);
    }

    public static boolean isDone(AssistantEvent e) {
        return e != null && AssistantEvent.DONE.equals(e.type());
    }

    public static boolean isFailed(AssistantEvent e) {
        return isDone(e) && e.data() != null && AssistantSession.FAILED.equals(e.data().path("status").asText());
    }

    /** The text of a message event, or the whole payload as text. */
    public static String textOf(AssistantEvent e) {
        if (e == null || e.data() == null) {
            return "";
        }
        if (e.data().isTextual()) {
            return e.data().asText();
        }
        return e.data().path("text").asText(e.data().path("message").asText(""));
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
