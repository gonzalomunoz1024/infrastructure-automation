package com.iaf.console.bff.types.domain;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** Everything the web app needs to draw a type's list, detail and forms. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConsoleType(
        String key,
        String type,
        String service,
        String plural,
        String title,
        String icon,
        String description,
        JsonNode schema,
        List<String> states,
        ListSpec list,
        DetailSpec detail,
        Map<String, String> lifecycle,
        Observe observe,
        List<ConsoleAction> actions,
        String etag) {

    public ConsoleType {
        states = states == null ? List.of() : List.copyOf(states);
        lifecycle = lifecycle == null ? Map.of() : Map.copyOf(lifecycle);
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public record ListSpec(List<String> columns, String defaultSort) {
    }

    public record DetailSpec(List<String> tabs) {
    }

    public record Observe(boolean supported, String interval, boolean metrics, boolean discover) {
    }

    public ConsoleType withActions(List<ConsoleAction> decided) {
        return new ConsoleType(key, type, service, plural, title, icon, description, schema, states, list, detail, lifecycle, observe, decided, etag);
    }

    public ConsoleType withEtag(String value) {
        return new ConsoleType(key, type, service, plural, title, icon, description, schema, states, list, detail, lifecycle, observe, actions, value);
    }
}
