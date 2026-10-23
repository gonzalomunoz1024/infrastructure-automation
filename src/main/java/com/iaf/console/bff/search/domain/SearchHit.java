package com.iaf.console.bff.search.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One resource the search indexer found: its name, type and state as indexed, the tags, the
 * relevance score and, per field, the matching text with the match wrapped in {@code <em>}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchHit(String wfrn, String type, String service, String region, String name, String state, Boolean drift, Map<String, String> tags,
        Double score, Map<String, String> highlights, Instant updatedAt) {

    public SearchHit {
        tags = copy(tags);
        highlights = copy(highlights);
    }

    static Map<String, String> copy(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            in.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(k, v);
                }
            });
        }
        return Map.copyOf(out);
    }
}
