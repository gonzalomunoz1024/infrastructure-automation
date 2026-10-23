package com.iaf.console.platform.client;

import java.util.List;

import com.iaf.console.platform.model.PlatformError;
import com.iaf.console.platform.model.Problem;

/**
 * A non-2xx answer of the platform: its status, the error body and, on a 429, the seconds to
 * wait. The status says what happened (404 unknown or out of scope, 409 a state conflict or an
 * idempotency key in flight, 422 an idempotency mismatch, 429 over a quota).
 */
public class PlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The error of an idempotency key reused with another request. */
    public static final String IDEMPOTENCY_MISMATCH = "idempotency.mismatch";

    private final int status;
    private final transient PlatformError error;
    private final Integer retryAfterSeconds;
    private final String correlationId;
    private final PlatformOperation operation;

    public PlatformException(PlatformOperation operation, int status, PlatformError error, Integer retryAfterSeconds, String correlationId) {
        super(message(operation, status, error));
        this.operation = operation;
        this.status = status;
        this.error = error == null ? new PlatformError("status " + status, correlationId, null, retryAfterSeconds, null, null) : error;
        this.retryAfterSeconds = retryAfterSeconds;
        this.correlationId = correlationId;
    }

    private static String message(PlatformOperation operation, int status, PlatformError error) {
        String what = error == null || error.error() == null ? "" : ": " + error.error();
        return (operation == null ? "platform" : operation.operationId()) + " answered " + status + what;
    }

    public int status() {
        return status;
    }

    public PlatformError error() {
        return error;
    }

    /** The {@code error} field of the body. */
    public String errorText() {
        return error.error();
    }

    public List<Problem> problems() {
        return error.problems() == null ? List.of() : error.problems();
    }

    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String correlationId() {
        return correlationId;
    }

    public PlatformOperation operation() {
        return operation;
    }

    public boolean isBadRequest() {
        return status == 400;
    }

    public boolean isUnauthorized() {
        return status == 401;
    }

    public boolean isForbidden() {
        return status == 403;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    public boolean isConflict() {
        return status == 409;
    }

    public boolean isTooManyRequests() {
        return status == 429;
    }

    /** True for an idempotency key reused with another request, whichever status the platform gives it. */
    public boolean isIdempotencyMismatch() {
        return status == 422 || IDEMPOTENCY_MISMATCH.equals(error.error());
    }

    /** True when a retry may succeed: 429, 503, 502, 504. */
    public boolean retryable() {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }
}
