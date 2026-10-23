package com.iaf.console.state.stack.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/** A parameter of a blueprint: what the launch form asks. Without a default it is required. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlueprintParameter(
        String type,
        String title,
        String description,
        @JsonProperty("default") JsonNode defaultValue,
        @JsonProperty("enum") List<JsonNode> enumValues,
        String pattern,
        Long minimum,
        Long maximum,
        @JsonProperty("x-resource") ResourceHint resource) {

    /** The type of registered resource whose registry id the value is. */
    public record ResourceHint(String type) {
    }

    public BlueprintParameter {
        enumValues = enumValues == null ? null : List.copyOf(enumValues);
    }

    @JsonProperty("required")
    public boolean required() {
        return defaultValue == null || defaultValue.isNull();
    }
}
