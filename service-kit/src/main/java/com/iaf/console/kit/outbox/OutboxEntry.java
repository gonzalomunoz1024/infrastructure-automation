package com.iaf.console.kit.outbox;

import java.time.Instant;

import com.iaf.console.kit.events.EventEnvelope;

/**
 * One stored event of the outbox: its sequence (the one total order of this service's events),
 * the topic and partition key it is published under, the envelope, and when it was published.
 */
public record OutboxEntry(long seq, String key, String topic, String partitionKey, EventEnvelope event, Instant createdAt, Instant publishedAt,
        int attempts) {

    public boolean published() {
        return publishedAt != null;
    }

    public OutboxEntry published(Instant at) {
        return new OutboxEntry(seq, key, topic, partitionKey, event, createdAt, at, attempts);
    }

    public OutboxEntry attempted() {
        return new OutboxEntry(seq, key, topic, partitionKey, event, createdAt, publishedAt, attempts + 1);
    }
}
