package com.iaf.console.bff.notifications.ports.outbound;

import java.time.Duration;
import java.time.Instant;

import com.iaf.console.bff.notifications.domain.OutboxEntry;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The emails and chat messages waiting to go (decision 32.2), keyed
 * {@code <eventKey>:<userId>:<channel>}: the first enqueue of a key wins on every replica, and a
 * worker holds what it claimed for a while so two replicas never send one message at once.
 */
public interface NotificationOutbox {

    /** Queues a delivery unless its key is queued already; true when this call queued it. */
    Mono<Boolean> enqueue(OutboxEntry entry);

    /** At most {@code max} entries due by {@code now} that no worker holds, each now held until {@code now + lease}. */
    Flux<OutboxEntry> claimDue(Instant now, Duration lease, int max);

    /** Records a try's outcome (sent, pending again with its next try, or failed) and lets go of the entry. */
    Mono<Void> save(OutboxEntry entry);
}
