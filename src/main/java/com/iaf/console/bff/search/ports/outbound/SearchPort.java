package com.iaf.console.bff.search.ports.outbound;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
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

    /** One page of hits, with the indexer's {@code didYouMean} when it sent one. */
    Mono<SearchPage> search(AccountScope scope, StateCaller caller, SearchQuery query);

    Mono<List<Suggestion>> suggest(AccountScope scope, StateCaller caller, String q, String type, int limit);

    Mono<ResourceCounts> counts(AccountScope scope, StateCaller caller, SearchQuery query);

    /**
     * A list's counts with every filter of the query, and its {@code groups} when it groups
     * ({@code /v1/counts} as it answers), asked as the person in their account: the query's
     * region only when it names one.
     */
    Mono<JsonNode> countGroups(AccountScope scope, StateCaller caller, CountsQuery query);

    /**
     * Plain English read as a list's filter ({@code /v1/search:interpret}), as the person in their
     * account, on the list of {@code type} when it is given; the indexer's answer as it is.
     */
    Mono<JsonNode> interpret(AccountScope scope, StateCaller caller, String q, String type);

    /**
     * The counts of 1 to {@value #MAX_ACCOUNTS} accounts added up ({@code /v1/counts} with
     * {@code accountId} repeated), as the console-internal principal; {@code scope} is the
     * session the console asks for, which no token names and the call is logged with.
     */
    Mono<JsonNode> countAcross(AccountScope scope, List<String> accountIds, CountsQuery query);

    /** How far the index is behind the events ({@code /v1/index/status}), asked as the person in their account. */
    Mono<IndexStatus> getIndexStatus(AccountScope scope, StateCaller caller);
}
