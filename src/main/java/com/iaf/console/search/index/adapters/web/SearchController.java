package com.iaf.console.search.index.adapters.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.auth.domain.ConsolePrincipal;
import com.iaf.console.search.boot.SearchProperties;
import com.iaf.console.search.index.application.SearchQueries;
import com.iaf.console.search.index.domain.QueryParser;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;

import reactor.core.publisher.Mono;

/**
 * The search surface of the contract ({@code contracts/openapi/console-search-indexer.yaml}): the
 * search, the type-ahead and a list's counts ({@code contracts/additions.md}), each as one account,
 * the caller's own unless the caller is internal and names one.
 */
@RestController
public final class SearchController {

    private final SearchQueries queries;
    private final String defaultRegion;

    public SearchController(SearchQueries queries, SearchProperties properties) {
        this.queries = queries;
        this.defaultRegion = properties.region();
    }

    /** The contract's suggestions: {@code items} only. */
    public record Suggestions(List<Suggestion> items) {
    }

    @GetMapping("/v1/search")
    public Mono<SearchPage> search(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Boolean drift,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String after) {
        ConsolePrincipal principal = Principals.of(exchange);
        SearchQuery query = QueryParser.parse(q, new QueryParser.Filters(type, state, region, tags(exchange), drift, sort, limit, after));
        return queries.search(scopeOf(principal, accountId, query), query);
    }

    /**
     * The counts of a list, for its header and state chips: the filters of a search ({@code q} may be
     * left out), the answer of {@link com.iaf.console.search.index.domain.ResourceCounts}.
     */
    @GetMapping("/v1/counts")
    public Mono<ResourceCounts> counts(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Boolean drift) {
        ConsolePrincipal principal = Principals.of(exchange);
        SearchQuery query = QueryParser.parse(q == null ? "" : q, new QueryParser.Filters(type, state, region, tags(exchange), drift, null, null, null));
        return queries.counts(scopeOf(principal, accountId, query), query);
    }

    private static Map<String, String> tags(ServerWebExchange exchange) {
        Map<String, String> tags = new LinkedHashMap<>();
        exchange.getRequest().getQueryParams().forEach((k, v) -> {
            if (k.startsWith("tag.") && !v.isEmpty()) {
                tags.put(k.substring(4), v.get(0));
            }
        });
        return tags;
    }

    private AccountScope scopeOf(ConsolePrincipal principal, String accountId, SearchQuery query) {
        return principal.scope(accountId, query.region() != null ? query.region() : principal.region() != null ? principal.region() : defaultRegion);
    }

    @GetMapping("/v1/suggest")
    public Mono<Suggestions> suggest(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit) {
        ConsolePrincipal principal = Principals.of(exchange);
        SuggestQuery query = SuggestQuery.of(q, type, limit);
        AccountScope scope = principal.scope(accountId, principal.region() != null ? principal.region() : defaultRegion);
        return queries.suggest(scope, query).map(Suggestions::new);
    }
}
