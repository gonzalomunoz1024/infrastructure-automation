package com.iaf.console.drift.auth.adapters.bff;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * How a call this service makes on its own to another console service names it (hub decision
 * 23): a service token the BFF issued for that audience ({@link ServiceTokens}), or, in the demos,
 * the dev header {@code X-Console-Service}, which only a service running with dev headers accepts.
 */
@FunctionalInterface
public interface ServiceCredentials {

    String SERVICE_HEADER = "X-Console-Service";

    /** The filter that authenticates every request a client sends to {@code audience}. */
    ExchangeFilterFunction forAudience(String audience);

    /** The demos' stand-in: the dev header naming {@code service}, whatever the audience. */
    static ServiceCredentials devHeader(String service) {
        ExchangeFilterFunction filter = (request, next) -> next.exchange(ClientRequest.from(request).header(SERVICE_HEADER, service).build());
        return audience -> filter;
    }
}
