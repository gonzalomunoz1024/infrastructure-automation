package com.iaf.console.kit.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishes the outbox in sequence order, one relay at a time: each tick takes or renews a lease,
 * drains the pending entries in batches and marks each one published once the publisher
 * acknowledged it. A failed publish is counted and retried on the next tick; the entries after
 * it wait, so order holds per partition.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPort outbox;
    private final EventPublisherPort publisher;
    private final Clock clock;
    private final String owner;
    private final Duration leaseTtl;
    private final int batchSize;
    private final AtomicBoolean ticking = new AtomicBoolean();
    private volatile Disposable schedule;

    public OutboxRelay(OutboxPort outbox, EventPublisherPort publisher, Clock clock) {
        this(outbox, publisher, clock, "relay-" + UUID.randomUUID(), Duration.ofSeconds(30), 200);
    }

    public OutboxRelay(OutboxPort outbox, EventPublisherPort publisher, Clock clock, String owner, Duration leaseTtl, int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.clock = clock;
        this.owner = owner;
        this.leaseTtl = leaseTtl;
        this.batchSize = batchSize;
    }

    /** One pass: the number of entries published, 0 when another relay holds the lease or nothing was pending. */
    public Mono<Long> tick() {
        if (!ticking.compareAndSet(false, true)) {
            return Mono.just(0L);
        }
        return outbox.acquireLease(owner, leaseTtl, clock.instant())
                .flatMap(held -> held ? drain() : Mono.just(0L))
                .onErrorResume(e -> {
                    log.warn("outbox relay tick failed: {}", e.toString());
                    return Mono.just(0L);
                })
                .doFinally(s -> ticking.set(false));
    }

    private Mono<Long> drain() {
        return outbox.pending(batchSize)
                .concatMap(this::publishOne)
                .takeWhile(ok -> ok)
                .count()
                .flatMap(n -> n == batchSize ? drain().map(more -> n + more) : Mono.just(n));
    }

    private Mono<Boolean> publishOne(OutboxEntry entry) {
        return publisher.publish(entry)
                .then(outbox.markPublished(entry.seq(), clock.instant()))
                .thenReturn(true)
                .onErrorResume(e -> {
                    log.warn("outbox entry {} ({}) could not be published, attempt {}: {}", entry.seq(), entry.key(), entry.attempts() + 1,
                            e.toString());
                    return outbox.markFailed(entry.seq()).thenReturn(false);
                });
    }

    /** Ticks every {@code interval} on a bounded elastic thread until {@link #stop()}. */
    public synchronized void start(Duration interval) {
        if (schedule != null) {
            return;
        }
        schedule = Flux.interval(interval, Schedulers.boundedElastic())
                .onBackpressureDrop()
                .concatMap(t -> tick())
                .subscribe();
    }

    public synchronized void stop() {
        if (schedule != null) {
            schedule.dispose();
            schedule = null;
        }
        outbox.releaseLease(owner).onErrorResume(e -> Mono.empty()).block(Duration.ofSeconds(5));
    }

    public boolean running() {
        return schedule != null;
    }

    public String owner() {
        return owner;
    }
}
