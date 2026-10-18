package com.iaf.platform.observability.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Which events make spans and count in the metrics: the ones happening now. The demo seeds weeks
 * of history into the past at startup; those events are older than {@link #WINDOW} and count for
 * nothing, so the metrics and traces describe what this process ran.
 */
final class LiveEvents {

    static final Duration WINDOW = Duration.ofMinutes(2);

    private LiveEvents() {
    }

    static boolean live(Instant at, Clock clock) {
        return at == null || !at.isBefore(clock.instant().minus(WINDOW));
    }
}
