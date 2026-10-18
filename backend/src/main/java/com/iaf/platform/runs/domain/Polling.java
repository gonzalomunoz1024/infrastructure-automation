package com.iaf.platform.runs.domain;

import java.time.Duration;

/**
 * How often a step that waits for something looks again: every {@code first} when
 * {@code first} and {@code max} are equal, else {@code first}, then twice the last wait after
 * every look up to {@code max}, and {@code first} again once what it watches changed. A
 * backed-off wait never sleeps past the step's deadline.
 *
 * @param first the wait before the second look
 * @param max   the longest wait between two looks
 */
public record Polling(Duration first, Duration max) {

    /** Every five seconds: the in-memory engine, and Temporal executions recorded before backed-off polls. */
    public static final Polling FIXED = new Polling(Duration.ofSeconds(5), Duration.ofSeconds(5));
    /** Five seconds, doubling up to five minutes: Temporal, where every look adds to the history. */
    public static final Polling BACKOFF = new Polling(Duration.ofSeconds(5), Duration.ofMinutes(5));

    /**
     * The wait before the next look.
     *
     * @param previous the last wait, null before the first one
     * @param changed  whether what the step watches changed since the last look
     * @param left     the time left before the step's deadline, null when it has none
     */
    public Duration next(Duration previous, boolean changed, Duration left) {
        if (first.equals(max)) {
            return first; // a fixed interval, as it always was: the deadline does not shorten it
        }
        Duration wait = previous == null || changed ? first : previous.multipliedBy(2);
        if (wait.compareTo(max) > 0) {
            wait = max;
        }
        if (left != null && !left.isNegative() && !left.isZero() && left.compareTo(wait) < 0) {
            wait = left;
        }
        return wait;
    }
}
