package com.iaf.console.fake.common;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The fake's clock: the system clock, with epoch nanoseconds that never repeat, so {@code updatedNs}
 * orders records strictly and resource event keys never collide.
 */
public final class FakeClock {

    private final Clock clock;
    private final AtomicLong lastNanos = new AtomicLong();

    public FakeClock(Clock clock) {
        this.clock = clock;
    }

    public Clock clock() {
        return clock;
    }

    public Instant now() {
        return clock.instant();
    }

    /** Epoch nanoseconds of now, strictly greater than any value answered before. */
    public long nanos() {
        Instant now = now();
        long candidate = Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        return lastNanos.updateAndGet(last -> Math.max(last + 1, candidate));
    }

    public static Instant fromNanos(long ns) {
        return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L));
    }

    public static long toNanos(Instant at) {
        return Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
    }
}
