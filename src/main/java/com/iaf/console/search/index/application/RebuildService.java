package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.errors.ConflictException;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.RebuildRun;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.RebuildRunPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;
import com.iaf.console.wfrn.Ulid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A rebuild: the state service's lists (live resources, then tombstones) of one account or of every
 * account walked into the index in batches, with the index serving all along; what the walk did
 * not see and was written before it started is removed at the end. One run at a time; the same
 * Idempotency-Key answers the same run.
 */
public final class RebuildService {

    private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

    private final SearchIndexPort index;
    private final StateServicePort state;
    private final AccountsPort accounts;
    private final RebuildRunPort runs;
    private final IndexingService indexing;
    private final IndexMetrics metrics;
    private final Clock clock;
    private final int batchSize;

    public RebuildService(SearchIndexPort index, StateServicePort state, AccountsPort accounts, RebuildRunPort runs, IndexingService indexing,
            IndexMetrics metrics, Clock clock, int batchSize) {
        this.index = index;
        this.state = state;
        this.accounts = accounts;
        this.runs = runs;
        this.indexing = indexing;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
    }

    /** Starts a run (202) unless one runs (409); the run then proceeds on its own. */
    public Mono<RebuildRun> start(String accountId, String idempotencyKey) {
        Mono<RebuildRun> replay = idempotencyKey == null || idempotencyKey.isBlank() ? Mono.empty() : runs.findByIdempotencyKey(idempotencyKey);
        return replay.switchIfEmpty(Mono.defer(() -> runs.findRunning()
                .flatMap(running -> Mono.<RebuildRun>error(new ConflictException("a rebuild is already running", running)))
                .switchIfEmpty(Mono.defer(() -> {
                    RebuildRun run = RebuildRun.start("rb_" + Ulid.next(), accountId, clock.instant(), idempotencyKey);
                    return runs.save(run).doOnNext(saved -> launch(saved));
                }))));
    }

    public Mono<RebuildRun> find(String runId) {
        return runs.find(runId);
    }

    public Mono<Boolean> rebuilding() {
        return runs.findRunning().map(r -> true).defaultIfEmpty(false);
    }

    /** The whole run, for a caller that wants to wait for it (the tests, a CLI). */
    public Mono<RebuildRun> run(RebuildRun run) {
        Instant startedAt = run.startedAt();
        AtomicLong indexed = new AtomicLong();
        Flux<String> targets = run.accountId() != null ? Flux.just(run.accountId()) : accounts.listAccountIds().concatWith(index.listAccountIds()).distinct();
        return targets.concatMap(accountId -> rebuildAccount(accountId, startedAt, indexed, run))
                .then(Mono.defer(() -> runs.save(run.succeeded(clock.instant(), indexed.get()))))
                .doOnNext(r -> {
                    metrics.rebuild("succeeded");
                    log.info("rebuild {} done: {} document(s)", r.runId(), r.indexed());
                })
                .onErrorResume(e -> {
                    log.error("rebuild {} failed after {} document(s): {}", run.runId(), indexed.get(), e.toString());
                    metrics.rebuild("failed");
                    return runs.save(run.failed(clock.instant(), indexed.get(), e.toString()));
                });
    }

    private void launch(RebuildRun run) {
        run(run).subscribeOn(Schedulers.boundedElastic()).subscribe(r -> {
        }, e -> log.error("rebuild {} could not be recorded: {}", run.runId(), e.toString()));
    }

    private Mono<Void> rebuildAccount(String accountId, Instant startedAt, AtomicLong indexed, RebuildRun run) {
        AccountScope scope = AccountScope.of(accountId, "global", IndexingService.SELF);
        Flux<com.iaf.console.search.index.domain.StateResource> live = state.list(scope, StateListing.everything());
        Flux<com.iaf.console.search.index.domain.StateResource> gone = state.list(scope, StateListing.everything().ofState(ResourceState.DELETED));
        return live.concatWith(gone)
                .concatMap(r -> indexing.documentOf(r, scope))
                .buffer(batchSize)
                .concatMap(batch -> index.indexAll(batch).flatMap(n -> {
                    long total = indexed.addAndGet(n);
                    return runs.save(run.progressed(total)).then();
                }))
                .then(index.removeStale(scope, startedAt).doOnNext(n -> {
                    if (n > 0) {
                        log.info("rebuild {}: {} document(s) of account {} were not in the state service any more", run.runId(), n, accountId);
                    }
                }))
                .then();
    }

    /** The batches a walk is written in. */
    public int batchSize() {
        return batchSize;
    }

    static List<String> none() {
        return List.of();
    }
}
