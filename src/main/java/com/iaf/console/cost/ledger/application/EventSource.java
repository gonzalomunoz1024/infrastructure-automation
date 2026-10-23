package com.iaf.console.cost.ledger.application;

import java.time.Instant;

import org.springframework.context.SmartLifecycle;

/** Where the events come from: the Kafka consumer, or the poller of the state service; each reports itself. */
public interface EventSource extends SmartLifecycle {

    record SourceStatus(String kind, boolean running, Instant lastEventAt, String lastError, long handled) {
    }

    SourceStatus status();
}
