package com.iaf.console.search.index.adapters.tokens;

import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import reactor.core.publisher.Mono;

/**
 * How this service names itself to another console service, as a filter on the WebClient that
 * calls it: the BFF's service token for that service (hub decision 23), or, in the demo, the dev
 * header {@code X-Console-Service}.
 */
@FunctionalInterface
public interface ServiceCredentials {

    /** The service id this service calls as, and the client id the BFF knows it by. */
    String SERVICE = "console-search-indexer";

    /** The filter for calls to one service, whose service id is the audience of its tokens. */
    ExchangeFilterFunction filterFor(String audience);

    /**
     * A bearer token for the callee on every request; when the callee answers 401 (the token
     * revoked, the BFF's keys rotated), the request goes once more with a new token.
     */
    static ServiceCredentials tokens(ServiceTokens tokens) {
        return audience -> (request, next) -> tokens.token(audience)
                .flatMap(token -> next.exchange(withBearer(request, token)).flatMap(response -> {
                    if (response.statusCode().value() != 401) {
                        return Mono.just(response);
                    }
                    tokens.refused(audience, token);
                    return response.releaseBody().then(tokens.token(audience)).flatMap(fresh -> next.exchange(withBearer(request, fresh)));
                }));
    }

    static ServiceCredentials devHeaders() {
        return audience -> (request, next) -> next.exchange(ClientRequest.from(request).header("X-Console-Service", SERVICE).build());
    }

    /** Nothing at all: the callee answers 401. */
    static ServiceCredentials none() {
        return audience -> (request, next) -> next.exchange(request);
    }

    private static ClientRequest withBearer(ClientRequest request, String token) {
        return ClientRequest.from(request).headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + token)).build();
    }
}
