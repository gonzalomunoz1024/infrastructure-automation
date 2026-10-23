package com.iaf.console.bff.assistant.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * A turn's body, the contract's {@code AssistantAsk}: the conversation to continue (a new one
 * when left out), {@code ask} with a question or {@code explain} with {@code context.wfrn}, and
 * where the person is.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssistantAsk(String conversationId, String intent, String message, AskContext context) {

    public static final String ASK = "ask";
    public static final String EXPLAIN = "explain";
    public static final int MAX_MESSAGE = 2000;

    /** The body of a request that sent none. */
    public static AssistantAsk empty() {
        return new AssistantAsk(null, null, null, null);
    }

    /**
     * The body's problems, each at its JSON pointer: {@code required} or {@code enum} at
     * {@code /intent}; {@code required} at {@code /message} for {@code ask}, {@code length} there
     * over 2000 characters; {@code required} at {@code /context/wfrn} for {@code explain}.
     */
    public List<Problem> problems() {
        List<Problem> problems = new ArrayList<>();
        if (intent == null || intent.isBlank()) {
            problems.add(Problem.error("required", "/intent", "intent is required: ask or explain"));
        } else if (!ASK.equals(intent) && !EXPLAIN.equals(intent)) {
            problems.add(Problem.error("enum", "/intent", "intent is ask or explain"));
        }
        if (message != null && message.codePointCount(0, message.length()) > MAX_MESSAGE) {
            problems.add(Problem.error("length", "/message", "message is at most " + MAX_MESSAGE + " characters"));
        } else if (ASK.equals(intent) && (message == null || message.isBlank())) {
            problems.add(Problem.error("required", "/message", "an ask needs a message"));
        }
        if (EXPLAIN.equals(intent) && (wfrn() == null || wfrn().isBlank())) {
            problems.add(Problem.error("required", "/context/wfrn", "explain needs context.wfrn"));
        }
        if (conversationId != null && conversationId.isBlank()) {
            problems.add(Problem.error("required", "/conversationId", "conversationId is a conversation's id, or left out for a new one"));
        }
        return problems;
    }

    /** The 400 for a body with problems. */
    public static InvalidInputException invalid(List<Problem> problems) {
        String message = problems.size() == 1 ? problems.get(0).message()
                : "the ask has " + problems.size() + " problems: " + problems.stream().map(Problem::message).collect(Collectors.joining("; "));
        return new InvalidInputException(message, problems);
    }

    /** The resource the person is on, when the context names one. */
    public String wfrn() {
        return context == null ? null : context.wfrn();
    }

    public boolean explains() {
        return EXPLAIN.equals(intent);
    }

    /** The ask with its message trimmed and its context reduced to what the copilot takes. */
    public AssistantAsk sanitized() {
        String kept = message == null || message.isBlank() ? null : message.strip();
        return new AssistantAsk(conversationId, intent, kept, context == null ? null : context.sanitized());
    }
}
