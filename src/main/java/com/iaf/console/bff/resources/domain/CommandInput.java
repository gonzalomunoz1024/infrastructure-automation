package com.iaf.console.bff.resources.domain;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A form's values for a create, an update, a verb or a preview; {@code confirm} for a destructive
 * verb, {@code labels} become job labels, and {@code tags} are a create's tags (every required
 * one named, WP 4.4), set on the resource before its workflow starts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandInput(Map<String, Object> input, Boolean confirm, String reason, Map<String, String> labels, Map<String, String> tags) {

    public CommandInput {
        input = input == null ? Map.of() : Map.copyOf(input);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    public static CommandInput empty() {
        return new CommandInput(Map.of(), null, null, Map.of(), Map.of());
    }

    public boolean confirmed() {
        return Boolean.TRUE.equals(confirm);
    }
}
