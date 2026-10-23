package com.iaf.console.fake.runs.domain;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** What a command answered: a status, a JSON body (null for 204) and extra headers; kept for idempotent replays. */
public record CommandResult(int status, JsonNode body, Map<String, String> headers) {

    public CommandResult {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static CommandResult of(int status, JsonNode body) {
        return new CommandResult(status, body, Map.of());
    }

    public static CommandResult accepted(JsonNode body) {
        return of(202, body);
    }

    public static CommandResult ok(JsonNode body) {
        return of(200, body);
    }

    public static CommandResult noContent() {
        return new CommandResult(204, null, Map.of());
    }

    public CommandResult withHeader(String name, String value) {
        Map<String, String> next = new java.util.LinkedHashMap<>(headers);
        next.put(name, value);
        return new CommandResult(status, body, next);
    }
}
