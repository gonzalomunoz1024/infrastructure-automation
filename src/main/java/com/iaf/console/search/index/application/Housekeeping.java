package com.iaf.console.search.index.application;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.search.index.domain.QueryBudget;
import com.iaf.console.search.index.ports.outbound.SearchIndexPort;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The chores on a timer: tombstones past their reservation leave the index, idle query budgets are
 * forgotten, and the documents gauge is counted again every half minute.
 */
public final class Housekeeping implements org.springframework.context.SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);
    private static final Duration GAUGES = Duration.ofSeconds(30);

    private final SearchIndexPort index;
    private final QueryBudget budget;
    private final IndexMetrics metrics;
    private final Clock clock;
    private final Duration interval;
    private volatile Disposable ticking;
    private volatile Disposable counting;

    public Housekeeping(SearchIndexPort index, QueryBudget budget, IndexMetrics metrics, Clock clock, Duration interval) {
        this.index = index;
        this.budget = budget;
        this.metrics = metrics;
        this.clock = clock;
        this.interval = interval;
    }

    /** The documents gauge, counted again; a failed count leaves it as it was. */
    public Mono<Long> countDocuments() {
        return index.count().doOnNext(metrics::documents).onErrorResume(e -> {
            log.debug("counting the documents failed: {}", e.toString());
            return Mono.empty();
        });
    }

    /** One pass: the number of tombstones expired. */
    public Mono<Long> tick() {
        budget.forgetIdle(Duration.ofHours(1).toSeconds());
        return index.expireTombstones(clock.instant())
                .doOnNext(n -> {
                    if (n > 0) {
                        log.info("{} tombstone(s) left the index", n);
                    }
                })
                .onErrorResume(e -> {
                    log.warn("expiring tombstones failed: {}", e.toString());
                    return Mono.just(0L);
                });
    }

    @Override
    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(interval, interval, Schedulers.boundedElastic()).onBackpressureDrop().concatMap(t -> tick(), 1).subscribe();
        counting = Flux.interval(Duration.ofSeconds(1), GAUGES, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(t -> countDocuments(), 1).subscribe();
    }

    @Override
    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
        if (counting != null) {
            counting.dispose();
            counting = null;
        }
    }

    @Override
    public boolean isRunning() {
        return ticking != null;
    }
}
