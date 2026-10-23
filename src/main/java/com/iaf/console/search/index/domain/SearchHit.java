package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One hit of the contract's {@code SearchHit}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchHit(
        String wfrn,
        String type,
        String service,
        String region,
        String name,
        ResourceState state,
        boolean drift,
        Map<String, String> tags,
        double score,
        Map<String, String> highlights,
        Instant updatedAt) {

    public SearchHit {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
        highlights = highlights == null || highlights.isEmpty() ? null : Map.copyOf(highlights);
    }

    public static SearchHit of(IndexedResource r, double score, Map<String, String> highlights) {
        return new SearchHit(r.wfrn(), r.type(), r.service(), r.region(), r.name(), r.state(), r.drift(), r.tags(), score, highlights, r.updatedAt());
    }
}
