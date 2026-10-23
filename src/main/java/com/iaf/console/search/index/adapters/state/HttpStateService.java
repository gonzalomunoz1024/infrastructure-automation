package com.iaf.console.search.index.adapters.state;

import java.time.Duration;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.kit.scope.AccountScope;
import com.iaf.console.search.index.domain.StateListing;
import com.iaf.console.search.index.domain.StateResource;
import com.iaf.console.search.index.ports.outbound.StateServicePort;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The state service over HTTP ({@code contracts/openapi/console-state-service.yaml}), as the
 * console-internal principal: the resource by wfrn ({@code 410} is its tombstone, {@code 404}
 * nothing), and the account's list page after page, oldest change first, so a walk can resume
 * from a watermark.
 */
public final class HttpStateService implements StateServicePort {

    private static final Logger log = LoggerFactory.getLogger(HttpStateService.class);

    private final WebClient web;
    private final ServiceCredentials credentials;

    public HttpStateService(WebClient web, ServiceCredentials credentials) {
        this.web = web;
        this.credentials = credentials;
    }

    @Override
    public Mono<StateResource> find(AccountScope scope, String wfrn) {
        // a URI variable is encoded strictly (":" and "/" too), as the contract asks; never encode it twice
        return web.get().uri("/v1/wfrn/{wfrn}", wfrn).headers(credentials::apply)
                .exchangeToMono(r -> {
                    int status = r.statusCode().value();
                    if (status == 404) {
                        return r.releaseBody().then(Mono.empty());
                    }
                    if (status == 410) {
                        return r.bodyToMono(JsonNode.class).map(StateResource::tombstone);
                    }
                    if (r.statusCode().is2xxSuccessful()) {
                        return r.bodyToMono(JsonNode.class).map(StateResource::of);
                    }
                    return failure(r);
                })
                .retryWhen(transient5xx());
    }

    @Override
    public Flux<StateResource> list(AccountScope scope, StateListing listing) {
        return page(scope, listing, null).expand(p -> p.next() == null ? Mono.empty() : page(scope, listing, p.next()))
                .concatMapIterable(Listed::items);
    }

    private record Listed(java.util.List<StateResource> items, String next) {
    }

    private Mono<Listed> page(AccountScope scope, StateListing listing, String after) {
        Function<UriBuilder, java.net.URI> uri = b -> {
            b.path("/v1/resources").queryParam("accountId", scope.accountId()).queryParam("sort", "updatedAt").queryParam("limit", listing.pageSize());
            if (listing.updatedAfter() != null) {
                b.queryParam("updatedAfter", listing.updatedAfter().toString());
            }
            if (listing.state() != null) {
                b.queryParam("state", listing.state().wire());
            }
            if (listing.type() != null) {
                b.queryParam("type", listing.type());
            }
            if (after != null) {
                b.queryParam("after", after);
            }
            return b.build();
        };
        return web.get().uri(uri).headers(credentials::apply)
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : HttpStateService.<JsonNode>failure(r))
                .retryWhen(transient5xx())
                .map(body -> {
                    java.util.List<StateResource> items = new java.util.ArrayList<>();
                    body.path("items").forEach(n -> items.add(StateResource.of(n)));
                    JsonNode next = body.get("next");
                    return new Listed(items, next == null || next.isNull() ? null : next.asText());
                });
    }

    private static <T> Mono<T> failure(ClientResponse r) {
        return r.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(text -> Mono.error(new StateServiceException(r.statusCode().value(), text)));
    }

    private static Retry transient5xx() {
        return Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))
                .filter(e -> e instanceof StateServiceException s ? s.status() >= 500 : !(e instanceof IllegalArgumentException))
                .doBeforeRetry(s -> log.debug("retrying the state service after {}", s.failure().toString()));
    }

    /** An answer of the state service that is not the one expected. */
    public static final class StateServiceException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        public StateServiceException(int status, String body) {
            super("the state service answered " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
