package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.IndexStatus;
import com.iaf.console.search.index.domain.QueryBudget;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;

/** The reads: a search, a type-ahead and a list's counts, each within the account's query budget, and the index's status. */
public final class SearchQueries {

    private final SearchIndexPort index;
    private final CheckpointPort checkpoints;
    private final RebuildService rebuilds;
    private final List<EventSource> sources;
    private final QueryBudget budget;
    private final IndexMetrics metrics;
    private final Clock clock;

    public SearchQueries(SearchIndexPort index, CheckpointPort checkpoints, RebuildService rebuilds, List<EventSource> sources, QueryBudget budget,
            IndexMetrics metrics, Clock clock) {
        this.index = index;
        this.checkpoints = checkpoints;
        this.rebuilds = rebuilds;
        this.sources = List.copyOf(sources);
        this.budget = budget;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<SearchPage> search(AccountScope scope, SearchQuery query) {
        return Mono.defer(() -> {
            spend(scope, "search");
            Timer.Sample sample = metrics.start();
            long started = System.nanoTime();
            return index.search(scope, query)
                    .map(page -> page.withTook(Math.max(1, (System.nanoTime() - started) / 1_000_000)))
                    .doOnSuccess(p -> {
                        metrics.stop(sample, "search");
                        metrics.query("search", "ok");
                    })
                    .doOnError(e -> metrics.query("search", "error"));
        });
    }

    public Mono<List<Suggestion>> suggest(AccountScope scope, SuggestQuery query) {
        return Mono.defer(() -> {
            spend(scope, "suggest");
            Timer.Sample sample = metrics.start();
            return index.suggest(scope, query)
                    .doOnSuccess(s -> {
                        metrics.stop(sample, "suggest");
                        metrics.query("suggest", "ok");
                    })
                    .doOnError(e -> metrics.query("suggest", "error"));
        });
    }

    /** The counts of a list (its header, its state chips), within the same budget as a search. */
    public Mono<ResourceCounts> counts(AccountScope scope, SearchQuery query) {
        return Mono.defer(() -> {
            spend(scope, "counts");
            Timer.Sample sample = metrics.start();
            return index.counts(scope, query)
                    .doOnSuccess(c -> {
                        metrics.stop(sample, "counts");
                        metrics.query("counts", "ok");
                    })
                    .doOnError(e -> metrics.query("counts", "error"));
        });
    }

    /**
     * The index's status. The documents are the scope's account's, for anyone who acts for an
     * account (nothing of another account is counted), or the whole index's when the scope is null,
     * for the console's own services; the newest event and the lag are the indexer's own.
     */
    public Mono<IndexStatus> status(AccountScope scope) {
        Mono<Long> documents = scope == null ? index.count().doOnNext(metrics::documents) : index.count(scope);
        return Mono.zip(documents, checkpoints.find(IndexingService.EVENTS).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty()),
                rebuilds.rebuilding())
                .map(t -> {
                    Instant now = clock.instant();
                    Instant last = t.getT2().map(c -> c.eventAt()).orElse(null);
                    for (EventSource source : sources) {
                        Instant at = source.status().lastEventAt();
                        if (at != null && (last == null || at.isAfter(last))) {
                            last = at;
                        }
                    }
                    double lag = last == null ? 0 : Math.max(0, Duration.between(last, now).toMillis() / 1000.0);
                    return new IndexStatus(t.getT1(), lag, last, t.getT3());
                });
    }

    private void spend(AccountScope scope, String kind) {
        int wait = budget.take(scope.accountId());
        if (wait > 0) {
            metrics.query(kind, "throttled");
            throw new TooManyRequestsException("over the account's query budget", wait);
        }
    }
}
