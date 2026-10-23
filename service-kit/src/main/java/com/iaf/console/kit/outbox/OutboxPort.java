package com.iaf.console.kit.outbox;

import java.time.Duration;
import java.time.Instant;

import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The transactional outbox: a command handler appends its events right after its own write, the
 * relay publishes them in sequence order under a lease. Appending an event whose key is already
 * stored is a no-op that answers the stored entry, so a re-emitted event is never published twice.
 */
@GlobalRead("the outbox holds this service's own events, which the relay reads as a whole")
public interface OutboxPort {

    /** Stores the event with the next sequence, or answers the entry that already holds its key. */
    Mono<OutboxEntry> append(String topic, String partitionKey, EventEnvelope event);

    /** The unpublished entries in sequence order, at most {@code limit}. */
    Flux<OutboxEntry> pending(int limit);

    /** Marks an entry published. */
    Mono<Void> markPublished(long seq, Instant at);

    /** Counts an attempt that failed, so a poison event can be noticed. */
    Mono<Void> markFailed(long seq);

    /** The highest sequence stored, 0 when empty. */
    Mono<Long> highWater();

    /** Takes or renews the relay lease for {@code owner}; false when another live owner holds it. */
    Mono<Boolean> acquireLease(String owner, Duration ttl, Instant now);

    /** Gives the lease back, when {@code owner} holds it. */
    Mono<Void> releaseLease(String owner);

    /** The stored entries from a sequence on, in order, for an HTTP feed or a rebuild. */
    Flux<OutboxEntry> after(long seq, int limit);
}
