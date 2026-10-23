package com.iaf.console.bff.search.ports.outbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * The search indexer. A person is asked for in their account: their token names it and the
 * indexer only ever searches that account's documents. Counts over several accounts are asked as
 * the console-internal principal, which names the accounts, so no person's token ever names
 * another account.
 */
public interface SearchPort {

    /** The most accounts one counts call names. */
    int MAX_ACCOUNTS = 200;

    Mono<Page<SearchHit>> search(AccountScope scope, StateCaller caller, SearchQuery query);

    Mono<List<Suggestion>> suggest(AccountScope scope, StateCaller caller, String q, String type, int limit);

    Mono<ResourceCounts> counts(AccountScope scope, StateCaller caller, SearchQuery query);

    /**
     * The counts of 1 to {@value #MAX_ACCOUNTS} accounts added up ({@code /v1/counts} with
     * {@code accountId} repeated), as the console-internal principal; {@code scope} is the
     * session the console asks for, which no token names and the call is logged with.
     */
    Mono<JsonNode> countAcross(AccountScope scope, List<String> accountIds, CountsQuery query);

    /** How far the index is behind the events ({@code /v1/index/status}), asked as the person in their account. */
    Mono<IndexStatus> getIndexStatus(AccountScope scope, StateCaller caller);
}
