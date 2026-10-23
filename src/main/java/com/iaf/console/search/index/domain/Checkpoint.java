package com.iaf.console.search.index.domain;

import java.time.Instant;
import java.util.Objects;

/** Where a source of events was last read: the newest event's time and when it was applied. */
public record Checkpoint(String source, Instant eventAt, Instant appliedAt, long applied) {

    public Checkpoint {
        Objects.requireNonNull(source, "source");
    }

    public static Checkpoint empty(String source) {
        return new Checkpoint(source, null, null, 0);
    }

    /** The checkpoint after one more event; the event time never moves backwards. */
    public Checkpoint advanced(Instant eventTime, Instant now) {
        Instant newest = eventAt == null || (eventTime != null && eventTime.isAfter(eventAt)) ? eventTime : eventAt;
        return new Checkpoint(source, newest, now, applied + 1);
    }
}
