package com.iaf.provider.sdk;

/**
 * The code behind an action: takes the validated input, returns the output. Throw a
 * {@link ProviderException} for a classified failure; any other exception is reported as the
 * retryable class {@code error}.
 *
 * @param <I> the input type: a record (its schema is derived) or {@code JsonNode}
 * @param <O> the output type: a record (its schema is derived) or {@code JsonNode}
 */
@FunctionalInterface
public interface Handler<I, O> {

    O handle(ActionContext ctx, I input) throws Exception;
}
