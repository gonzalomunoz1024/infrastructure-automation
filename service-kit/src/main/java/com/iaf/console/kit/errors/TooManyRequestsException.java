package com.iaf.console.kit.errors;

/** Over a quota or a rate limit: a 429 with {@code Retry-After} in seconds. */
public class TooManyRequestsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int retryAfterSeconds;

    public TooManyRequestsException(String message, int retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
