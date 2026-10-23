package com.iaf.console.drift;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock the tests move by hand. */
public final class TestClock extends Clock {

    private final AtomicReference<Instant> now;

    public TestClock(Instant start) {
        this.now = new AtomicReference<>(start);
    }

    public void advance(Duration by) {
        now.updateAndGet(t -> t.plus(by));
    }

    public void set(Instant at) {
        now.set(at);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
