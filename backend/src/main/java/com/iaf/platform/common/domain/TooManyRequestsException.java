package com.iaf.platform.common.domain;

import java.time.Duration;

/** The caller is over a rate limit (429); {@link #retryAfterSeconds()} says when to try again. */
public class TooManyRequestsException extends RuntimeException {
    private final Duration retryAfter;

    public TooManyRequestsException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter == null ? Duration.ofSeconds(1) : retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** Whole seconds for the {@code Retry-After} header, rounded up, at least 1. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
