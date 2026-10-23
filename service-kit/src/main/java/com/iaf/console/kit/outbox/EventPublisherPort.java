package com.iaf.console.kit.outbox;

import reactor.core.publisher.Mono;

/** Where the relay publishes an outbox entry: Kafka in production, a sink in memory for the demo and the tests. */
public interface EventPublisherPort {

    /** Publishes one entry under its topic and partition key; completes when the broker acknowledged it. */
    Mono<Void> publish(OutboxEntry entry);
}
