package com.iaf.console.cost.ledger.application;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Every interval, writes the deltas a crash left pending on rate records, so no change of rate is ever lost. */
public final class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final LedgerService ledger;
    private final Duration interval;
    private volatile Disposable ticking;

    public Housekeeping(LedgerService ledger, Duration interval) {
        this.ledger = ledger;
        this.interval = interval;
    }

    public synchronized void start() {
        if (ticking != null) {
            return;
        }
        ticking = Flux.interval(interval, interval, Schedulers.boundedElastic()).onBackpressureDrop()
                .concatMap(t -> ledger.repairPending().onErrorResume(e -> {
                    log.warn("repairing pending deltas failed: {}", e.toString());
                    return Mono.just(0L);
                }), 1).subscribe();
    }

    public synchronized void stop() {
        if (ticking != null) {
            ticking.dispose();
            ticking = null;
        }
    }
}
