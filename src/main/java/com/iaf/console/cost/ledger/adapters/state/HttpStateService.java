package com.iaf.console.cost.ledger.adapters.state;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.iaf.console.cost.ledger.adapters.tokens.ServiceTokens;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * The state service over HTTP ({@code contracts/openapi/console-state-service.yaml}), as the
 * console-internal principal (the WebClient carries this service's credentials): the resource by
 * wfrn ({@code 410} is its tombstone, of a delete or of a move to another account, which names the
 * wfrn it became; {@code 404} nothing), and the account's list page after page, oldest change
 * first, so a walk can resume from a watermark.
 */
public final class HttpStateService implements StateServicePort {

    private static final Logger log = LoggerFactory.getLogger(HttpStateService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 200;

    private final WebClient web;

    public HttpStateService(WebClient web) {
        this.web = web;
    }

    @Override
    public Mono<ResourceSnapshot> find(AccountScope scope, String wfrn) {
        // a URI variable is encoded strictly (":" and "/" too), as the contract asks; never encode it twice
        return web.get().uri("/v1/wfrn/{wfrn}", wfrn)
                .exchangeToMono(r -> {
                    int status = r.statusCode().value();
                    if (status == 404) {
                        return r.releaseBody().then(Mono.<ResourceSnapshot>empty());
                    }
                    if (status == 410) {
                        // gone, whatever the body holds: one that names nothing is still the tombstone of the wfrn asked for
                        return r.bodyToMono(String.class).defaultIfEmpty("").map(text -> ResourceSnapshot.tombstone(wfrn, tombstoneBody(text)));
                    }
                    if (r.statusCode().is2xxSuccessful()) {
                        return r.bodyToMono(JsonNode.class).map(ResourceSnapshot::of);
                    }
                    return failure(r);
                })
                .retryWhen(transient5xx());
    }

    @Override
    public Flux<ResourceSnapshot> list(AccountScope scope, Instant updatedAfter, boolean deleted, String type) {
        return page(scope, updatedAfter, deleted, type, null).expand(p -> p.next() == null ? Mono.empty() : page(scope, updatedAfter, deleted, type, p.next()))
                .concatMapIterable(Listed::items);
    }

    private record Listed(List<ResourceSnapshot> items, String next) {
    }

    private Mono<Listed> page(AccountScope scope, Instant updatedAfter, boolean deleted, String type, String after) {
        Function<UriBuilder, URI> uri = b -> {
            b.path("/v1/resources").queryParam("accountId", scope.accountId()).queryParam("sort", "updatedAt").queryParam("limit", PAGE);
            if (updatedAfter != null) {
                b.queryParam("updatedAfter", updatedAfter.toString());
            }
            if (deleted) {
                b.queryParam("state", "deleted");
            }
            if (type != null) {
                b.queryParam("type", type);
            }
            if (after != null) {
                b.queryParam("after", after);
            }
            return b.build();
        };
        return web.get().uri(uri)
                .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class) : HttpStateService.<JsonNode>failure(r))
                .retryWhen(transient5xx())
                .map(body -> {
                    List<ResourceSnapshot> items = new ArrayList<>();
                    body.path("items").forEach(n -> items.add(ResourceSnapshot.of(n)));
                    JsonNode next = body.get("next");
                    return new Listed(items, next == null || next.isNull() ? null : next.asText());
                });
    }

    /** A 410's body as JSON ({@code Tombstone}): an empty object when it is empty or not a JSON object. */
    private static JsonNode tombstoneBody(String text) {
        try {
            JsonNode body = text.isBlank() ? null : JSON.readTree(text);
            return body != null && body.isObject() ? body : JsonNodeFactory.instance.objectNode();
        } catch (JsonProcessingException e) {
            return JsonNodeFactory.instance.objectNode();
        }
    }

    private static <T> Mono<T> failure(ClientResponse r) {
        return r.bodyToMono(String.class).defaultIfEmpty("")
                .flatMap(text -> Mono.error(new StateServiceException(r.statusCode().value(), text)));
    }

    /** A 5xx or a lost connection is asked again; a refusal (4xx, or the BFF's for a token) is not. */
    private static Retry transient5xx() {
        return Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))
                .filter(e -> e instanceof StateServiceException s ? s.status() >= 500
                        : e instanceof ServiceTokens.Refused r ? r.status() >= 500 : !(e instanceof IllegalArgumentException))
                .doBeforeRetry(s -> log.debug("retrying the state service after {}", s.failure().toString()))
                .onRetryExhaustedThrow((spec, signal) -> signal.failure());
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
