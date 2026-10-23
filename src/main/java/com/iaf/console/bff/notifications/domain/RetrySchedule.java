package com.iaf.console.bff.notifications.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * When an email or a chat message is tried again (decision 32.2): the first try is at once, a
 * failed one is tried again after 1, 5 and 30 minutes and then 2 hours, and after the fifth try
 * fails the delivery is marked failed.
 */
public final class RetrySchedule {

    public static final List<Duration> DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
    /** Every try a delivery gets: the first and one per delay. */
    public static final int MAX_ATTEMPTS = DELAYS.size() + 1;

    private RetrySchedule() {
    }

    /** The wait before the next try after {@code attempts} tries have failed; empty when that was the last. */
    public static Optional<Duration> after(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("a delivery fails after one try at least");
        }
        return attempts <= DELAYS.size() ? Optional.of(DELAYS.get(attempts - 1)) : Optional.empty();
    }
}
