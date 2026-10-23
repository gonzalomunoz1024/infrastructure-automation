package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;
import com.iaf.console.kit.errors.TooManyRequestsException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.CountGroup;
import com.iaf.console.search.index.domain.DidYouMean;
import com.iaf.console.search.index.domain.GroupBy;
import com.iaf.console.search.index.domain.IndexStatus;
import com.iaf.console.search.index.domain.Interpretation;
import com.iaf.console.search.index.domain.PlainEnglish;
import com.iaf.console.search.index.domain.QueryBudget;
import com.iaf.console.search.index.domain.QueryParser;
import com.iaf.console.search.index.domain.ResourceCounts;
import com.iaf.console.search.index.domain.SearchPage;
import com.iaf.console.search.index.domain.SearchQuery;
import com.iaf.console.search.index.domain.SuggestQuery;
import com.iaf.console.search.index.domain.Suggestion;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.ResourceTypesPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The reads: a search, a type-ahead, a list's counts (grouped, and over several accounts) and plain
 * English read as a filter, each within the account's query budget, and the index's status.
 */
public final class SearchQueries {

    /** How many accounts' counts are read at once when one call counts several. */
    private static final int ACCOUNTS_AT_ONCE = 16;

    private final SearchIndexPort index;
    private final ResourceTypesPort types;
    private final CheckpointPort checkpoints;
    private final RebuildService rebuilds;
    private final List<EventSource> sources;
    private final QueryBudget budget;
    private final IndexMetrics metrics;
    private final Clock clock;

    public SearchQueries(SearchIndexPort index, ResourceTypesPort types, CheckpointPort checkpoints, RebuildService rebuilds, List<EventSource> sources,
            QueryBudget budget, IndexMetrics metrics, Clock clock) {
        this.index = index;
        this.types = types;
        this.checkpoints = checkpoints;
        this.rebuilds = rebuilds;
        this.sources = List.copyOf(sources);
        this.budget = budget;
        this.metrics = metrics;
        this.clock = clock;
    }

    public Mono<SearchPage> search(AccountScope scope, SearchQuery query) {
        return search(scope, query, null);
    }

    /** A search; {@code typed}, the query as the person typed it, is what a did-you-mean fixes. */
    public Mono<SearchPage> search(AccountScope scope, SearchQuery query, String typed) {
        return Mono.defer(() -> {
            spend(scope, "search");
            Timer.Sample sample = metrics.start();
            long started = System.nanoTime();
            return index.search(scope, query)
                    .map(page -> page.withDidYouMean(DidYouMean.of(typed, query, page.items())))
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
        return counts(List.of(scope), query, GroupBy.NONE);
    }

    /**
     * The counts of a list over one account or several (an organization's, for the console's own
     * services), grouped when asked: each account is read on its own, within its own budget, and
     * the counts are added up, the groups by key, the largest {@link GroupBy#MAX_GROUPS} kept.
     */
    public Mono<ResourceCounts> counts(List<AccountScope> scopes, SearchQuery query, GroupBy groupBy) {
        return Mono.defer(() -> {
            scopes.forEach(s -> spend(s, "counts"));
            Timer.Sample sample = metrics.start();
            return Flux.fromIterable(scopes).flatMapSequential(s -> countsOf(s, query, groupBy), ACCOUNTS_AT_ONCE)
                    .reduce(ResourceCounts::plus)
                    .map(c -> c.largest(GroupBy.MAX_GROUPS))
                    .doOnSuccess(c -> {
                        metrics.stop(sample, "counts");
                        metrics.query("counts", "ok");
                    })
                    .doOnError(e -> metrics.query("counts", "error"));
        });
    }

    private Mono<ResourceCounts> countsOf(AccountScope scope, SearchQuery query, GroupBy groupBy) {
        if (groupBy.kind() == GroupBy.Kind.ACCOUNT) {
            return index.counts(scope, query, GroupBy.NONE).map(c -> c.withGroups(List.of(CountGroup.of(scope.accountId(), c))));
        }
        return index.counts(scope, query, groupBy);
    }

    /**
     * Plain English read as a list's filter ({@link PlainEnglish}), with how many resources it lists.
     * The words the reader could only guess at stay as free text while the search still finds
     * something; when it finds nothing with them and something without, they are left out.
     */
    public Mono<Interpretation> interpret(AccountScope scope, String text, String within) {
        return Mono.defer(() -> {
            if (text == null || text.isBlank() || text.length() > QueryParser.MAX_QUERY_LENGTH) {
                throw new InvalidInputException("q cannot be read",
                        List.of(Problem.error("length", "/q", "q is 1 to " + QueryParser.MAX_QUERY_LENGTH + " characters")));
            }
            spend(scope, "interpret");
            Timer.Sample sample = metrics.start();
            Instant now = clock.instant();
            return Mono.zip(types.list().collectList(), index.vocabulary(scope))
                    .flatMap(t -> {
                        PlainEnglish.Reading reading = PlainEnglish.read(text, t.getT1(), t.getT2(), now, within);
                        return total(scope, reading, now).flatMap(n -> {
                            if (n > 0 || !reading.hasGuesses()) {
                                return Mono.just(reading.answer(n));
                            }
                            PlainEnglish.Reading without = reading.withoutGuesses();
                            return total(scope, without, now).map(m -> m > 0 ? without.answer(m) : reading.answer(0));
                        });
                    })
                    .doOnSuccess(i -> {
                        metrics.stop(sample, "interpret");
                        metrics.query("interpret", "ok");
                    })
                    .doOnError(e -> metrics.query("interpret", "error"));
        });
    }

    /** What the list shows with the reading applied as the console applies it: {@code filter.q} in the search box, the rest as its filters. */
    private Mono<Long> total(AccountScope scope, PlainEnglish.Reading reading, Instant now) {
        QueryParser.Filters filters = new QueryParser.Filters(reading.type(), reading.state() == null ? null : reading.state().wire(), reading.region(),
                reading.tags(), reading.drift(), null, null, null);
        return index.counts(scope, QueryParser.parse(reading.filterQ(), filters, now)).map(ResourceCounts::total);
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
