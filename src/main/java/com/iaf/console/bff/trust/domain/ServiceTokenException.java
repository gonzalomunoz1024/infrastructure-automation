package com.iaf.console.bff.trust.domain;

/**
 * A refused token request, as RFC 6749 §5.2 names it: {@code invalid_request},
 * {@code unsupported_grant_type} and {@code invalid_target} (RFC 8707) answer 400,
 * {@code invalid_client} 401, {@code slow_down} (over the client's rate) 429 with Retry-After. The message says why; it never repeats the assertion.
 */
public final class ServiceTokenException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String error;
    private final int status;
    private final long retryAfterSeconds;

    private ServiceTokenException(String error, int status, String message) {
        this(error, status, message, 0);
    }

    private ServiceTokenException(String error, int status, String message, long retryAfterSeconds) {
        super(message);
        this.error = error;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    /** 429 {@code slow_down}: the client asked for more tokens than its rate allows; ask again after {@code Retry-After}. */
    public static ServiceTokenException slowDown(String message, long retryAfterSeconds) {
        return new ServiceTokenException("slow_down", 429, message, retryAfterSeconds);
    }

    /** Seconds to wait before asking again; 0 unless the client was slowed down. */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ServiceTokenException invalidRequest(String message) {
        return new ServiceTokenException("invalid_request", 400, message);
    }

    public static ServiceTokenException unsupportedGrantType(String message) {
        return new ServiceTokenException("unsupported_grant_type", 400, message);
    }

    public static ServiceTokenException invalidTarget(String message) {
        return new ServiceTokenException("invalid_target", 400, message);
    }

    public static ServiceTokenException invalidClient(String message) {
        return new ServiceTokenException("invalid_client", 401, message);
    }

    public String error() {
        return error;
    }

    public int status() {
        return status;
    }
}
