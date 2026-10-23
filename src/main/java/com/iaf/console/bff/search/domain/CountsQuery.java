package com.iaf.console.bff.search.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What to count on the search indexer's {@code /v1/counts}: free text and terms in its syntax,
 * one type, one region (every region when {@code null}), one state, tags (an empty value is any
 * value), drift, and the grouping ({@code type}, {@code service}, {@code state}, {@code region},
 * {@code account} or {@code tag:<name>}; no groups when {@code null}).
 */
public record CountsQuery(String q, String type, String region, String state, Map<String, String> tags, Boolean drift, String groupBy) {

    public CountsQuery {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }

    public boolean grouped() {
        return groupBy != null;
    }

    public CountsQuery withType(String other) {
        return new CountsQuery(q, other, region, state, tags, drift, groupBy);
    }
}
