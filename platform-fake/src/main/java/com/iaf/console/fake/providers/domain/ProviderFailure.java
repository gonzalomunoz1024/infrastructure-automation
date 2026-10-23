package com.iaf.console.fake.providers.domain;

/** A provider call failed the way a real one would: with an error class, a message and whether a retry may help. */
public final class ProviderFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String errorClass;
    private final boolean retryable;
    private final int status;

    public ProviderFailure(String errorClass, String message, boolean retryable, int status) {
        super(message);
        this.errorClass = errorClass;
        this.retryable = retryable;
        this.status = status;
    }

    public String errorClass() {
        return errorClass;
    }

    public boolean retryable() {
        return retryable;
    }

    /** The HTTP-like status of the failure; 404 for not found. */
    public int status() {
        return status;
    }

    public boolean notFound() {
        return status == 404;
    }
}
