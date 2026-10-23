package com.iaf.console.identity.elevations.adapters;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.identity.elevations.application.ElevationExpiry;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runs the elevation expiry sweep every {@code sweep-every} while the service is up. */
public final class ElevationExpirySchedule implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ElevationExpirySchedule.class);

    private final ElevationExpiry expiry;
    private final Clock clock;
    private final Duration every;
    private Disposable schedule;

    public ElevationExpirySchedule(ElevationExpiry expiry, Clock clock, Duration every) {
        this.expiry = expiry;
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
                .concatMap(t -> expiry.sweep(clock.instant())
                        .doOnNext(n -> {
                            if (n > 0) {
                                log.info("expired {} elevations", n);
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("the elevation expiry sweep failed: {}", e.toString());
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
