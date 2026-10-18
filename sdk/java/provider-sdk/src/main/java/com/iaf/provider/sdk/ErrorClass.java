package com.iaf.provider.sdk;

/**
 * An error class a provider's actions fail with, as the provider file publishes it. Retryable
 * classes may be retried by the step's retry policy; the others fail the step at once.
 *
 * @param name        the class, like {@code pool_exhausted}; workflows match on it
 * @param retryable   whether a retry can succeed
 * @param description what it means, for the people who write workflows (may be null)
 */
public record ErrorClass(String name, boolean retryable, String description) {
}
