package com.iaf.console.state.stack.application;

import com.iaf.console.kit.correlation.Correlation;
import com.iaf.console.kit.events.EventEnvelope;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;
import com.iaf.console.state.stack.domain.StackEvent;

import reactor.core.publisher.Mono;

/**
 * Puts a stack event on the outbox as the {@code state.stacks} message of {@code console-events.yaml}:
 * the platform's envelope with {@code aggregate: stack}, the stack's wfrn as {@code aggregateId}, in
 * the context and as the partition key, and the key {@code <type>:<wfrn>:<version>}. The same
 * envelope is what {@code GET /v1/stacks/{wfrn}/events} answers.
 */
public final class StackEventPublisher {

    /** The topic of every stack event. */
    public static final String TOPIC = "state.stacks";

    private final OutboxPort outbox;

    public StackEventPublisher(OutboxPort outbox) {
        this.outbox = outbox;
    }

    public Mono<OutboxEntry> publish(StackEvent event) {
        return Mono.deferContextual(ctx -> outbox.append(TOPIC, event.wfrn(), envelope(event).withTraceparent(Correlation.traceparent(ctx).orElse(null))));
    }

    public static EventEnvelope envelope(StackEvent event) {
        return EventEnvelope.of(event.id(), event.key(), event.type().wire(), event.at(), "stack", event.wfrn(), event.actor(), event.data().deepCopy())
                .withContext(new EventEnvelope.Context(event.accountId(), event.region(), event.wfrn(), event.requestId()), null);
    }
}
