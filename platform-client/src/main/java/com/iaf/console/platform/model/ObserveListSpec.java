package com.iaf.console.platform.model;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A paged read action of a type: where its items and its cursor are. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ObserveListSpec(String action, Map<String, Object> input, String items, String id, CursorSpec cursor, Integer pageSize) {

    /** The input field a cursor goes back in and the response field it comes from. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CursorSpec(String request, String response) {
    }
}
