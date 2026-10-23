package com.iaf.console.kit.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import com.iaf.console.kit.events.EventEnvelope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** The outbox of the demo profile and the tests: in memory, one instance, nothing survives a restart. */
public final class InMemoryOutbox implements OutboxPort {

    private final ConcurrentSkipListMap<Long, OutboxEntry> bySeq = new ConcurrentSkipListMap<>();
    private final Map<String, Long> byKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Object appendLock = new Object();
    private final Object leaseLock = new Object();
    private String leaseOwner;
    private Instant leaseUntil = Instant.EPOCH;

    @Override
    public Mono<OutboxEntry> append(String topic, String partitionKey, EventEnvelope event) {
        return Mono.fromSupplier(() -> {
            synchronized (appendLock) {
                Long existing = byKey.get(event.key());
                if (existing != null) {
                    return bySeq.get(existing);
                }
                long seq = sequence.incrementAndGet();
                Instant now = event.at() == null ? Instant.now() : event.at();
                OutboxEntry entry = new OutboxEntry(seq, event.key(), topic, partitionKey, event.withSeq(seq), now, null, 0);
                bySeq.put(seq, entry);
                byKey.put(event.key(), seq);
                return entry;
            }
        });
    }

    @Override
    public Flux<OutboxEntry> pending(int limit) {
        return Flux.fromIterable(bySeq.values()).filter(e -> !e.published()).take(Math.max(0, limit));
    }

    @Override
    public Mono<Void> markPublished(long seq, Instant at) {
        return Mono.fromRunnable(() -> bySeq.computeIfPresent(seq, (k, e) -> e.published(at)));
    }

    @Override
    public Mono<Void> markFailed(long seq) {
        return Mono.fromRunnable(() -> bySeq.computeIfPresent(seq, (k, e) -> e.attempted()));
    }

    @Override
    public Mono<Long> highWater() {
        return Mono.fromSupplier(sequence::get);
    }

    @Override
    public Mono<Boolean> acquireLease(String owner, Duration ttl, Instant now) {
        return Mono.fromSupplier(() -> {
            synchronized (leaseLock) {
                boolean free = leaseOwner == null || owner.equals(leaseOwner) || !now.isBefore(leaseUntil);
                if (!free) {
                    return false;
                }
                leaseOwner = owner;
                leaseUntil = now.plus(ttl);
                return true;
            }
        });
    }

    @Override
    public Mono<Void> releaseLease(String owner) {
        return Mono.fromRunnable(() -> {
            synchronized (leaseLock) {
                if (owner.equals(leaseOwner)) {
                    leaseOwner = null;
                    leaseUntil = Instant.EPOCH;
                }
            }
        });
    }

    @Override
    public Flux<OutboxEntry> after(long seq, int limit) {
        return Flux.fromIterable(bySeq.tailMap(seq, false).values()).take(Math.max(0, limit));
    }

    /** Every stored entry, for tests. */
    public Flux<OutboxEntry> all() {
        return Flux.fromIterable(bySeq.values());
    }
}
