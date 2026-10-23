package com.iaf.console.state.stack.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** An output of a blueprint, or of a stack once resolved: a title and a text that may hold references. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BlueprintOutput(String title, String description, String value) {
}
