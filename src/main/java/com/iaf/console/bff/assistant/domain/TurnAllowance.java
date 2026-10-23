package com.iaf.console.bff.assistant.domain;

import java.time.Duration;

/** Whether a person may start a turn now, and when they may when not: the wait is never under a second. */
public record TurnAllowance(boolean taken, Duration retryAfter) {

    private static final TurnAllowance TAKEN = new TurnAllowance(true, Duration.ZERO);

    public static TurnAllowance granted() {
        return TAKEN;
    }

    public static TurnAllowance refused(Duration wait) {
        return new TurnAllowance(false, wait == null || wait.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1) : wait);
    }

    /** The wait in whole seconds, rounded up, for {@code Retry-After}. */
    public int retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }
}
