package com.iaf.console.drift.scorecards.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** A value the person supplies when they start a fix: its name, title, type, allowed values and default. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemediationInput(String name, String title, String type, List<JsonNode> enumeration, JsonNode defaultValue, boolean required) {

    public RemediationInput {
        type = type == null ? "string" : type;
        enumeration = enumeration == null ? null : List.copyOf(enumeration);
    }
}
