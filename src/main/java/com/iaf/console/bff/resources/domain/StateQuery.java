package com.iaf.console.bff.resources.domain;

import java.util.Map;

/** The filters of a list of resources, as the state service's {@code GET /v1/resources} takes them. */
public record StateQuery(String type, String state, String name, Map<String, String> tags, String wfrn, Boolean drift, String sort,
        Integer limit, String after) {

    public StateQuery {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
