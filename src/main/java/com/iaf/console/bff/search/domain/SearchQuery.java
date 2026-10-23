package com.iaf.console.bff.search.domain;

import java.util.Map;

/**
 * What to search the account's resources for: free text in the indexer's syntax ({@code tag:},
 * {@code type:}, {@code state:}, {@code drift:true} terms and wfrn patterns allowed), optionally one
 * type, one state and tags, an order (the console's list sorts, relevance when {@code null}) and a page.
 */
public record SearchQuery(String q, String type, String state, Map<String, String> tags, Boolean drift, String sort, Integer limit, String after) {

    public SearchQuery {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
