package com.iaf.console.search.index.adapters.web;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.auth.domain.ConsolePrincipal;
import com.iaf.console.search.boot.SearchProperties;
import com.iaf.console.search.index.application.SearchQueries;
import com.iaf.console.search.index.domain.GroupBy;
import com.iaf.console.search.index.domain.Interpretation;
import com.iaf.console.search.index.domain.QueryParser;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;

import reactor.core.publisher.Mono;

/**
 * The search surface of the contract ({@code contracts/openapi/console-search-indexer.yaml}): the
 * search, the type-ahead, a list's counts and plain English read as a filter, each as one account,
 * the caller's own unless the caller is internal and names one; an internal caller, or one of the
 * counting services (hub decision 35), may count several.
 */
@RestController
public final class SearchController {

    /** The most accounts one call may count, for the console's own services summing an organization. */
    public static final int MAX_ACCOUNTS = 200;

    private final SearchQueries queries;
    private final String defaultRegion;
    private final Clock clock;

    public SearchController(SearchQueries queries, SearchProperties properties, Clock clock) {
        this.queries = queries;
        this.defaultRegion = properties.region();
        this.clock = clock;
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
        SearchQuery query = QueryParser.parse(q, new QueryParser.Filters(type, state, region, tags(exchange), drift, sort, limit, after), clock.instant());
        return queries.search(scopeOf(principal, accountId, query), query, q);
    }

    /**
     * Plain English read as a list's filter, with how many resources it lists ({@code Interpretation}).
     * {@code type} is the list the person is on, which the reading stays in.
     */
    @GetMapping("/v1/search:interpret")
    public Mono<Interpretation> interpret(ServerWebExchange exchange,
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type) {
        ConsolePrincipal principal = Principals.of(exchange);
        AccountScope scope = principal.scope(accountId, principal.region() != null ? principal.region() : defaultRegion);
        return queries.interpret(scope, q, type == null || type.isBlank() ? null : type.trim());
    }

    /**
     * The counts of a list, for its header and state chips: the filters of a search ({@code q} may be
     * left out), the answer of {@link com.iaf.console.search.index.domain.ResourceCounts}, grouped
     * when {@code groupBy} asks ({@link GroupBy}). The console's own services may name up to
     * {@value #MAX_ACCOUNTS} accounts ({@code accountId} repeated) to count an organization; a person
     * names only their own.
     */
    @GetMapping("/v1/counts")
    public Mono<ResourceCounts> counts(ServerWebExchange exchange,
            @RequestParam(required = false) List<String> accountId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Boolean drift,
            @RequestParam(required = false) String groupBy) {
        ConsolePrincipal principal = Principals.of(exchange);
        SearchQuery query = QueryParser.parse(q == null ? "" : q, new QueryParser.Filters(type, state, region, tags(exchange), drift, null, null, null),
                clock.instant());
        GroupBy by = GroupBy.of(groupBy);
        List<String> accounts = accountId == null ? List.of() : accountId.stream().filter(a -> a != null && !a.isBlank()).map(String::trim).distinct()
                .toList();
        if (accounts.size() > MAX_ACCOUNTS) {
            throw new InvalidInputException("too many accounts",
                    List.of(Problem.error("maxItems", "/accountId", "at most " + MAX_ACCOUNTS + " accounts in one call")));
        }
        String inRegion = query.region() != null ? query.region() : principal.region() != null ? principal.region() : defaultRegion;
        List<AccountScope> scopes = accounts.isEmpty() ? List.of(principal.countScope(null, inRegion))
                : accounts.stream().map(a -> principal.countScope(a, inRegion)).toList();
        return queries.counts(scopes, query, by);
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
