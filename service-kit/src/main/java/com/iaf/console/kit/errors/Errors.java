package com.iaf.console.kit.errors;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The error body every console service answers with, the platform's shape:
 * {@code { error, problems?, correlationId? }}, plus {@code retryAfter} on a 429 and {@code detail}
 * on a 409.
 */
public final class Errors {

    /** What a 500 says: never the cause, which the correlation id finds in the log. */
    public static final String UNEXPECTED = "unexpected error";

    private Errors() {
    }

    /** The body as JSON; a null field is left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Body(String error, List<Problem> problems, String correlationId, Integer retryAfter, Object detail) {

        public Body withCorrelationId(String id) {
            return new Body(error, problems, id, retryAfter, detail);
        }
    }

    public static Body of(String error) {
        return new Body(error == null ? UNEXPECTED : error, null, null, null, null);
    }

    public static Body withProblems(String error, List<Problem> problems) {
        return new Body(error, problems == null || problems.isEmpty() ? null : List.copyOf(problems), null, null, null);
    }

    public static Body unexpected(String correlationId) {
        return new Body(UNEXPECTED, null, correlationId, null, null);
    }

    public static Body tooManyRequests(String error, int retryAfterSeconds) {
        return new Body(error, null, null, retryAfterSeconds, null);
    }

    public static Body conflict(String error, Object detail) {
        return new Body(error, null, null, null, detail);
    }
}
