package com.iaf.console.identity.accessreviews.adapters;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.identity.accessreviews.application.AccessReviewSweep;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runs the access review sweep at the clock's instant every {@code sweep-every} (15 minutes) while the service is up. */
public final class AccessReviewSchedule implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AccessReviewSchedule.class);

    private final AccessReviewSweep sweep;
    private final Clock clock;
    private final Duration every;
    private Disposable schedule;

    public AccessReviewSchedule(AccessReviewSweep sweep, Clock clock, Duration every) {
        this.sweep = sweep;
        this.clock = clock;
        this.every = every;
    }

    @Override
    public synchronized void start() {
        if (schedule != null) {
            return;
        }
        schedule = Flux.interval(every, every, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(t -> Mono.defer(() -> sweep.sweep(clock.instant()))
                        .doOnNext(r -> {
                            if (r.closed() + r.expired() + r.started() > 0) {
                                log.info("access reviews: closed {}, expired {} items, started {} scheduled", r.closed(), r.expired(), r.started());
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("the access review sweep failed: {}", e.toString());
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @Override
    public synchronized void stop() {
        if (schedule != null) {
            schedule.dispose();
            schedule = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return schedule != null;
    }
}
