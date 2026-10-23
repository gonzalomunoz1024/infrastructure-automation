package com.iaf.console.bff.search.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One page of search hits: the hits, the next page's cursor ({@code null} on the last page, but
 * always written) and, on the first page of a relevance search whose free text matched only a
 * typo away, {@code didYouMean}: the query with each such word as the hits spell it.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SearchPage(List<SearchHit> items, String next, @JsonInclude(JsonInclude.Include.NON_NULL) String didYouMean) {

    public SearchPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
