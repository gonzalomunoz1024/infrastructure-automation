package com.iaf.console.search.index.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One page of hits: {@code next} is on every page (null on the last), {@code total} when counting was
 * cheap and absent otherwise, {@code didYouMean} the query with its typos fixed when its words were
 * found only by fixing them ({@link DidYouMean}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SearchPage(List<SearchHit> items, @JsonInclude(JsonInclude.Include.ALWAYS) String next, Long total, long tookMs, String didYouMean) {

    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public SearchPage(List<SearchHit> items, String next, Long total, long tookMs) {
        this(items, next, total, tookMs, null);
    }

    public SearchPage withTook(long ms) {
        return new SearchPage(items, next, total, ms, didYouMean);
    }

    public SearchPage withDidYouMean(String q) {
        return new SearchPage(items, next, total, tookMs, q);
    }
}
