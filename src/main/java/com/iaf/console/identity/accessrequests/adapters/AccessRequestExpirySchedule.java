package com.iaf.console.identity.accessrequests.adapters;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.iaf.console.identity.accessrequests.application.AccessRequestExpiry;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Runs the expiry sweep every {@link #EVERY} while the service is up. */
public final class AccessRequestExpirySchedule implements SmartLifecycle {

    public static final Duration EVERY = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(AccessRequestExpirySchedule.class);

    private final AccessRequestExpiry expiry;
    private Disposable schedule;

    public AccessRequestExpirySchedule(AccessRequestExpiry expiry) {
        this.expiry = expiry;
    }

    @Override
    public synchronized void start() {
        if (schedule != null) {
            return;
        }
        schedule = Flux.interval(EVERY, EVERY, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(t -> expiry.sweep()
                        .doOnNext(n -> {
                            if (n > 0) {
                                log.info("expired {} access requests", n);
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("the access request expiry sweep failed: {}", e.toString());
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
