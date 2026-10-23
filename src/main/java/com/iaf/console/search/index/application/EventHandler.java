package com.iaf.console.search.index.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.idempotency.IdempotentConsumer;

import reactor.core.publisher.Mono;

/**
 * The entry of every console event, from Kafka or anywhere else: once per {@code key} (a
 * re-emitted event has a new id and the same key), then the fold. An event that fails is
 * forgotten again, so the next delivery gets another chance.
 */
public final class EventHandler {

    private static final Logger log = LoggerFactory.getLogger(EventHandler.class);

    private final IndexingService indexing;
    private final IdempotentConsumer once;

    public EventHandler(IndexingService indexing, IdempotentConsumer once) {
        this.indexing = indexing;
        this.once = once;
    }

    /** True when the event was handled now, false when its key was seen before. */
    public Mono<Boolean> handle(EventEnvelope event) {
        return once.process(event.key(), () -> indexing.apply(event).doOnNext(outcome -> {
            if (log.isDebugEnabled()) {
                log.debug("{} {} -> {}", event.type(), event.aggregateId(), outcome);
            }
        }).then());
    }
}
