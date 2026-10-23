package com.iaf.console.bff.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.search.domain.SearchPage;
import com.iaf.console.bff.search.domain.SearchQuery;
import com.iaf.console.bff.search.domain.Suggestion;
import com.iaf.console.bff.search.ports.outbound.SearchPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/** The search indexer for the search application's unit tests: each answer as the test sets it, each call recorded. */
final class FakeSearchIndex implements SearchPort {

    /** One call: what was asked, for whom, with which accounts (the internal principal's) and query. */
    record Call(String method, AccountScope scope, StateCaller caller, List<String> accounts, Object query) {
    }

    final List<Call> calls = new ArrayList<>();
    BiFunction<List<String>, CountsQuery, JsonNode> across = (accounts, query) -> {
        throw new UnsupportedOperationException("countAcross");
    };
    Function<CountsQuery, JsonNode> groups = query -> {
        throw new UnsupportedOperationException("countGroups");
    };
    BiFunction<String, String, JsonNode> reading = (q, type) -> {
        throw new UnsupportedOperationException("interpret");
    };
    Function<AccountScope, IndexStatus> status = scope -> {
        throw new UnsupportedOperationException("getIndexStatus");
    };

    List<Call> calls(String method) {
        return calls.stream().filter(c -> c.method().equals(method)).toList();
    }

    @Override
    public Mono<JsonNode> countAcross(AccountScope scope, List<String> accountIds, CountsQuery query) {
        calls.add(new Call("countAcross", scope, null, accountIds, query));
        return Mono.fromCallable(() -> across.apply(accountIds, query));
    }

    @Override
    public Mono<JsonNode> countGroups(AccountScope scope, StateCaller caller, CountsQuery query) {
        calls.add(new Call("countGroups", scope, caller, List.of(), query));
        return Mono.fromCallable(() -> groups.apply(query));
    }

    @Override
    public Mono<JsonNode> interpret(AccountScope scope, StateCaller caller, String q, String type) {
        calls.add(new Call("interpret", scope, caller, List.of(), type == null ? q : q + " @" + type));
        return Mono.fromCallable(() -> reading.apply(q, type));
    }

    @Override
    public Mono<IndexStatus> getIndexStatus(AccountScope scope, StateCaller caller) {
        calls.add(new Call("getIndexStatus", scope, caller, List.of(), null));
        return Mono.fromCallable(() -> status.apply(scope));
    }

    @Override
    public Mono<SearchPage> search(AccountScope scope, StateCaller caller, SearchQuery query) {
        throw new UnsupportedOperationException("search");
    }

    @Override
    public Mono<List<Suggestion>> suggest(AccountScope scope, StateCaller caller, String q, String type, int limit) {
        throw new UnsupportedOperationException("suggest");
    }

    @Override
    public Mono<ResourceCounts> counts(AccountScope scope, StateCaller caller, SearchQuery query) {
        throw new UnsupportedOperationException("counts");
    }
}
