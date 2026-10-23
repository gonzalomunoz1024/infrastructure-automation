package com.iaf.console.search.index.ports.outbound;

import java.time.Instant;
import java.util.List;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.kit.scope.GlobalRead;
import com.iaf.console.search.index.domain.AccountVocabulary;
import com.iaf.console.search.index.domain.GroupBy;
import com.iaf.console.search.index.domain.IndexedResource;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The index itself: OpenSearch in production, an in-memory twin for the demo and the tests. A
 * write carries the resource's version and never overtakes a newer one (external versioning,
 * greater or equal); every read of documents is scoped to one account.
 */
public interface SearchIndexPort {

    /** Makes the index exist with its mapping; nothing when it does. */
    Mono<Void> ensureReady();

    /** Writes a document, unless the index holds a newer version of it. */
    Mono<Void> index(IndexedResource document);

    /** Writes many documents at once (a rebuild); the number written. */
    Mono<Integer> indexAll(List<IndexedResource> documents);

    /** Forgets a resource the state service no longer knows at all (a 404, not a tombstone). */
    Mono<Void> remove(String accountId, String wfrn);

    /** One document of the scope's account, empty when there is none. */
    Mono<IndexedResource> find(AccountScope scope, String wfrn);

    Mono<SearchPage> search(AccountScope scope, SearchQuery query);

    Mono<List<Suggestion>> suggest(AccountScope scope, SuggestQuery query);

    /** How many documents of the scope's account a query lists, in all and per state (see {@link ResourceCounts}). */
    default Mono<ResourceCounts> counts(AccountScope scope, SearchQuery query) {
        return counts(scope, query, GroupBy.NONE);
    }

    /**
     * The counts, and the same counts per key of a grouping (a field or a tag; {@link GroupBy#field()}
     * not null), the largest first, at most {@link GroupBy#ENGINE_GROUPS}. Any other grouping returns
     * no groups.
     */
    Mono<ResourceCounts> counts(AccountScope scope, SearchQuery query, GroupBy groupBy);

    /** The types, regions and tags of the account's live documents, for reading plain English. */
    Mono<AccountVocabulary> vocabulary(AccountScope scope);

    /** How many documents the index holds, tombstones included. */
    @GlobalRead("a count of documents across every account, for the status, never their contents")
    Mono<Long> count();

    /** How many documents the scope's account has, tombstones included. */
    Mono<Long> count(AccountScope scope);

    /** Removes the scope's documents written before an instant: what a rebuild did not see is gone from the state service. */
    Mono<Long> removeStale(AccountScope scope, Instant indexedBefore);

    /** Removes the tombstones whose reservation ended. */
    Mono<Long> expireTombstones(Instant now);

    /** The accounts with documents, for a rebuild of every account. */
    @GlobalRead("the account ids that have documents, no document of any of them")
    Flux<String> listAccountIds();
}
