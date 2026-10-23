package com.iaf.console.bff.search.ports.outbound;

import java.util.List;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.kit.paging.Page;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The search indexer, asked as the person in their account: it only ever searches that account's documents. */
public interface SearchPort {

    Mono<Page<SearchHit>> search(AccountScope scope, StateCaller caller, SearchQuery query);

    Mono<List<Suggestion>> suggest(AccountScope scope, StateCaller caller, String q, String type, int limit);

    Mono<ResourceCounts> counts(AccountScope scope, StateCaller caller, SearchQuery query);
}
