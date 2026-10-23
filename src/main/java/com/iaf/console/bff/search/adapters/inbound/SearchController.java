package com.iaf.console.bff.search.adapters.inbound;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.bff.search.application.Search;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.bff.session.application.CurrentSession;
import com.iaf.console.kit.paging.Page;

import reactor.core.publisher.Mono;

/**
 * Global search ({@code /console/v1/search}), type-ahead ({@code /console/v1/suggest}) and a
 * list's counts ({@code /console/v1/r/{type}:counts}: the total, one count per state for the
 * filter chips, and how many have drifted), all through the search indexer.
 */
@RestController
public class SearchController {

    private final Search search;

    public SearchController(Search search) {
        this.search = search;
    }

    /** The type-ahead's answer. */
    public record Suggestions(List<Suggestion> items) {
    }

    @GetMapping(path = "/console/v1/search", produces = "application/json")
    public Mono<Page<SearchHit>> search(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "type", required = false) String type,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "after", required = false) String after,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> search.search(session, q, type, limit, after));
    }

    @GetMapping(path = "/console/v1/suggest", produces = "application/json")
    public Mono<Suggestions> suggest(@RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "limit", required = false) Integer limit,
            ServerWebExchange exchange) {
        return CurrentSession.required(exchange).flatMap(session -> search.suggest(session, q, limit)).map(Suggestions::new);
    }

    @GetMapping(path = "/console/v1/r/{type:[a-z0-9-]+}:counts", produces = "application/json")
    public Mono<ResourceCounts> counts(@PathVariable("type") String type,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "state", required = false) String state,
            @RequestParam(name = "drift", required = false) Boolean drift,
            ServerWebExchange exchange) {
        Map<String, String> tags = new LinkedHashMap<>();
        exchange.getRequest().getQueryParams().forEach((name, values) -> {
            if (name.startsWith("tag.") && name.length() > 4 && !values.isEmpty()) {
                tags.put(name.substring(4), values.get(0));
            }
        });
        return CurrentSession.required(exchange).flatMap(session -> search.counts(session, type, q, state, tags, drift));
    }
}
