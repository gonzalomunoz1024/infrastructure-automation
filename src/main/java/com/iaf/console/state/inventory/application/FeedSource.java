package com.iaf.console.state.inventory.application;

import java.time.Instant;

/** A reader of the platform's feed (over HTTP or Kafka) that can say where it is, for the internal status endpoint. */
public interface FeedSource {

    /** Where a feed reader is: the last sequence applied, whether it runs, and its last error. */
    record FeedStatus(String name, boolean running, long lastSeq, Instant lastEventAt, String lastError, long handled) {
    }

    FeedStatus status();
}
