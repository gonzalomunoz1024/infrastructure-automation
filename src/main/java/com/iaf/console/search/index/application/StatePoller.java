package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.Checkpoint;
import com.iaf.console.search.index.domain.ResourceState;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.ports.outbound.AccountsPort;
import com.iaf.console.search.index.ports.outbound.CheckpointPort;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Where Kafka is not there (the demo, a laptop), the index follows the state service by polling
 * its lists: every account's resources changed after the watermark, live and deleted, each
 * interval. A watermark per account is kept as a checkpoint, moved back a little each time so a
 * change that landed in the same instant is not missed (the writes are idempotent by version). The
 * documents go to the index in bulk, a batch at a time, so a catch-up after a pause is quick and
 * a refresh is waited for once per batch, not once per document.
 */
public final class StatePoller implements EventSource {

    public static final String SOURCE = "poll";

    private static final Logger log = LoggerFactory.getLogger(StatePoller.class);
    private static final Duration OVERLAP = Duration.ofSeconds(1);
    private static final int BATCH = 200;

    private final StateServicePort state;
    private final AccountsPort accounts;
    private final SearchIndexPort index;
    private final IndexingService indexing;
    private final CheckpointPort checkpoints;
    private final Clock clock;
    private final Duration interval;
    private final AtomicLong handled = new AtomicLong();
    private final AtomicReference<Instant> lastEventAt = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicLong failures = new AtomicLong();
    private volatile Disposable ticking;

    public StatePoller(StateServicePort state, AccountsPort accounts, SearchIndexPort index, IndexingService indexing, CheckpointPort checkpoints,
            Clock clock, Duration interval) {
        this.state = state;
        this.accounts = accounts;
        this.index = index;
        this.indexing = indexing;
        this.checkpoints = checkpoints;
        this.clock = clock;
        this.interval = interval;
    }

    /** One pass over every account: the number of documents written. A pass with no failure clears the last error. */
    public Mono<Long> tick() {
        return Mono.defer(() -> {
            long failedBefore = failures.get();
            return accounts.listAccountIds().concatWith(index.listAccountIds()).distinct()
                    .concatMap(this::poll)
                    .reduce(0L, Long::sum)
                    .doOnSuccess(n -> {
                        if (failures.get() == failedBefore) {
                            // every account caught up from its checkpoint, so the last failure is behind it
                            lastError.set(null);
                        }
                    })
                    .doOnError(e -> {
                        failures.incrementAndGet();
                        lastError.set(e.toString());
                        log.warn("the poll failed: {}", e.toString());
                    });
        });
    }

    private Mono<Long> poll(String accountId) {
        AccountScope scope = AccountScope.of(accountId, "global", IndexingService.SELF);
        String source = SOURCE + ":" + accountId;
        return checkpoints.find(source).defaultIfEmpty(Checkpoint.empty(source)).flatMap(checkpoint -> {
            Instant since = checkpoint.eventAt() == null ? null : checkpoint.eventAt().minus(OVERLAP);
            StateListing listing = StateListing.since(since);
            AtomicReference<Instant> newest = new AtomicReference<>(checkpoint.eventAt());
            Flux<com.iaf.console.search.index.domain.StateResource> changed = state.list(scope, listing)
                    .concatWith(state.list(scope, listing.ofState(ResourceState.DELETED)));
            return changed
                    .concatMap(r -> indexing.documentOf(r, scope))
                    .buffer(BATCH)
                    .concatMap(batch -> index.indexAll(batch).thenMany(Flux.fromIterable(batch)))
                    .doOnNext(doc -> {
                        handled.incrementAndGet();
                        Instant at = doc.updatedAt() != null ? doc.updatedAt() : doc.deletedAt();
                        if (at != null) {
                            newest.updateAndGet(n -> n == null || at.isAfter(n) ? at : n);
                            lastEventAt.updateAndGet(n -> n == null || at.isAfter(n) ? at : n);
                        }
                    })
                    .count()
                    .flatMap(n -> {
                        Instant top = newest.get();
                        if (top == null || top.equals(checkpoint.eventAt())) {
                            return Mono.just(n);
                        }
                        return checkpoints.save(new Checkpoint(source, top, clock.instant(), checkpoint.applied() + n)).thenReturn(n);
                    })
                    .onErrorResume(e -> {
                        failures.incrementAndGet();
                        lastError.set(e.toString());
                        log.warn("polling account {} failed: {}", accountId, e.toString());
                        return Mono.just(0L);
                    });
        });
    }

    @Override
    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        log.info("following the state service by polling every {}", interval);
        ticking = Flux.interval(Duration.ZERO, interval, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(t -> tick().onErrorResume(e -> Mono.just(0L)), 1)
                .subscribe();
    }

    @Override
    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }

    @Override
    public boolean isRunning() {
        return ticking != null;
    }

    @Override
    public SourceStatus status() {
        return new SourceStatus(SOURCE, isRunning(), lastEventAt.get(), lastError.get(), handled.get());
    }
}
