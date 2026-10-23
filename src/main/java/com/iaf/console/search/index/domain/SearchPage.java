package com.iaf.console.search.index.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One page of hits: {@code next} is on every page (null on the last), {@code total} when counting was cheap. */
public record SearchPage(List<SearchHit> items, @JsonInclude(JsonInclude.Include.ALWAYS) String next, Long total, long tookMs) {

    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public SearchPage withTook(long ms) {
        return new SearchPage(items, next, total, ms);
    }
}
