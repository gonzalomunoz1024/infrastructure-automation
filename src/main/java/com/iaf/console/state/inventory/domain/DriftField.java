package com.iaf.console.state.inventory.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

/** One field a finding is about: a JSON pointer into the attributes, what was desired and what the provider reports. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DriftField(String path, JsonNode expected, JsonNode observed) {
}
