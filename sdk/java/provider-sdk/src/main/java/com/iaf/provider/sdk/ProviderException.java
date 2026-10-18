package com.iaf.provider.sdk;

/**
 * A classified failure of an action. The worker reports it to the platform with its error class;
 * a retryable one may be retried by the step's retry policy, a fatal one fails the step at once.
 * Throw it from a handler: {@code throw ctx.fatal("pool_exhausted", "No free address in office")}.
 */
public class ProviderException extends RuntimeException {

    private final String errorClass;
    private final boolean retryable;

    public ProviderException(String errorClass, String message, boolean retryable) {
        this(errorClass, message, retryable, null);
    }

    public ProviderException(String errorClass, String message, boolean retryable, Throwable cause) {
        super(message == null ? errorClass : message, cause);
        if (errorClass == null || errorClass.isBlank()) {
            throw new IllegalArgumentException("an error needs an error class");
        }
        this.errorClass = errorClass;
        this.retryable = retryable;
    }

    /** A failure a retry can fix: the system was busy, a lock was held, a dependency was down. */
    public static ProviderException retryable(String errorClass, String message) {
        return new ProviderException(errorClass, message, true);
    }

    /** A failure no retry can fix: the input names something that does not exist, a quota is used up. */
    public static ProviderException fatal(String errorClass, String message) {
        return new ProviderException(errorClass, message, false);
    }

    public String errorClass() {
        return errorClass;
    }

    public boolean retryable() {
        return retryable;
    }
}
