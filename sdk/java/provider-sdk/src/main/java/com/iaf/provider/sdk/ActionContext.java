package com.iaf.provider.sdk;

/**
 * What a handler knows about the call it serves, and what it can tell the platform while it runs.
 */
public interface ActionContext {

    /** The action being run (the activity type). */
    String action();

    /**
     * The key to make the call's side effect idempotent by: the same for every retry of the step
     * (and for a worker that picks the call up again after a crash), new for a new run of it.
     * Pass it to the system as its idempotency or request id, or keep it with what you created.
     */
    String idempotencyKey();

    /** The job the call belongs to. */
    JobInfo job();

    /** The id of the workflow step that made the call. */
    String step();

    /** The environment the job runs against ({@code dev}, {@code prod}); may be null. */
    String environment();

    /** The attempt of the step, from 1; a retry by the step's retry policy counts up. */
    int attempt();

    /**
     * Tell the platform the call is alive, with optional progress details. Call it more often
     * than the provider's heartbeat timeout during long work. When the step was cancelled or the
     * call already timed out, it throws; let that exception propagate.
     */
    void heartbeat(Object details);

    /** A failure a retry can fix; throw it. */
    default ProviderException retryable(String errorClass, String message) {
        return ProviderException.retryable(errorClass, message);
    }

    /** A failure no retry can fix; throw it. */
    default ProviderException fatal(String errorClass, String message) {
        return ProviderException.fatal(errorClass, message);
    }
}
