package com.iaf.console.kit.idempotency;

import java.time.Duration;
import java.time.Instant;

import com.iaf.console.kit.scope.GlobalRead;

import reactor.core.publisher.Mono;

/** The keys of the events this service already handled, kept for a while so a replay is recognised. */
@GlobalRead("processed event keys are this service's own bookkeeping, not tenant data")
public interface ProcessedEventsPort {

    /** Records the key unless it is there: true when it is new, false when the event was handled before. */
    Mono<Boolean> markIfNew(String key, Instant now, Duration ttl);

    /** Forgets a key, so a handler that failed can run again on the next delivery. */
    Mono<Void> forget(String key);
}
