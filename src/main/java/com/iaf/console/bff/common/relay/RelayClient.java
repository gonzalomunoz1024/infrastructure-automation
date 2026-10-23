package com.iaf.console.bff.common.relay;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.iaf.console.bff.resources.domain.StateCaller;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * A console service's HTTP API, called as its contract says: an internal JWT for the person in
 * their account, or a service token (no account) for the BFF acting on its own; query values sent
 * as strictly encoded URI variables. A 2xx or 4xx answer comes back as it was, for the caller to
 * pass on; a 5xx is a {@link WebClientResponseException} (502), no answer within the timeout a
 * {@link java.util.concurrent.TimeoutException} (504).
 */
public final class RelayClient {

    /** The headers of an answer that are passed back to the browser. */
    public static final Set<String> RELAYED_HEADERS = Set.of("etag", "content-disposition", "x-content-sha256", "idempotent-replay", "location",
            "retry-after");

    private final WebClient.Builder builder;
    private final Supplier<String> baseUrl;
    private final Trust trust;
    private final String audience;
    private final Duration timeout;
    private volatile WebClient client;

    public RelayClient(WebClient.Builder builder, Supplier<String> baseUrl, Trust trust, String audience, Duration timeout) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.trust = trust;
        this.audience = audience;
        this.timeout = timeout;
    }

    public String url() {
        return baseUrl.get();
    }

    /** The call as the person, in the scope's account and region. */
    public Mono<Relayed> asPerson(AccountScope scope, StateCaller caller, RelayCall call) {
        return Mono.defer(() -> exchange(call, "Bearer " + trust.mint(InternalToken.user(audience, caller.userId(), scope.accountId(), scope.region(),
                caller.groups(), caller.roles(), caller.rolesUntil()))));
    }

    /** The call as the BFF itself: a service token, which may name any account. */
    public Mono<Relayed> asService(RelayCall call) {
        return Mono.defer(() -> exchange(call, "Bearer " + trust.mint(InternalToken.service(audience, trust.issuer()))));
    }

    /** The same call with another timeout (the budget check's own, shorter one). */
    public Mono<Relayed> asService(RelayCall call, Duration within) {
        return Mono.defer(() -> send(call, "Bearer " + trust.mint(InternalToken.service(audience, trust.issuer()))).timeout(within));
    }

    private Mono<Relayed> exchange(RelayCall call, String bearer) {
        return send(call, bearer).timeout(timeout);
    }

    private Mono<Relayed> send(RelayCall call, String bearer) {
        WebClient.RequestBodySpec request = client().method(HttpMethod.valueOf(call.method())).uri(uri(call))
                .header(HttpHeaders.AUTHORIZATION, bearer);
        call.headers().forEach(request::header);
        WebClient.RequestHeadersSpec<?> ready = request;
        if (call.body() != null) {
            ready = request.contentType(MediaType.APPLICATION_JSON).bodyValue(call.body());
        }
        return ready.exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            if (response.statusCode().is5xxServerError()) {
                return Mono.<Relayed>error(failure(response, bytes));
            }
            return Mono.just(relayed(response, bytes));
        }));
    }

    /** The whole address, built here so nothing encodes it twice: the path as given (its segments encoded), every query name and value strictly encoded. */
    private URI uri(RelayCall call) {
        String base = baseUrl.get().replaceAll("/+$", "");
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> param : call.query()) {
            query.append(query.length() == 0 ? '?' : '&').append(strict(param.getKey())).append('=').append(strict(param.getValue()));
        }
        return URI.create(base + call.path() + query);
    }

    /** A query name or value with everything but the unreserved characters percent-encoded. */
    static String strict(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** One path segment, encoded: a wfrn's slash becomes {@code %2F}. */
    public static String segment(String value) {
        return strict(value).replace("%3A", ":");
    }

    private static Relayed relayed(ClientResponse response, byte[] bytes) {
        Map<String, String> headers = new HashMap<>();
        response.headers().asHttpHeaders().forEach((name, values) -> {
            if (RELAYED_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT)) && !values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        String type = response.headers().contentType().map(MediaType::toString).orElse(bytes.length == 0 ? null : MediaType.APPLICATION_JSON_VALUE);
        return new Relayed(response.statusCode().value(), type, headers, bytes);
    }

    private static WebClientResponseException failure(ClientResponse response, byte[] bytes) {
        return WebClientResponseException.create(response.statusCode().value(), "upstream answered " + response.statusCode().value(),
                response.headers().asHttpHeaders(), bytes, StandardCharsets.UTF_8);
    }

    private WebClient client() {
        WebClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = builder.clone().build();
                    client = c;
                }
            }
        }
        return c;
    }

    /** Query parameters from name and value pairs, in order. */
    public static List<Map.Entry<String, String>> params(String... nameValues) {
        java.util.ArrayList<Map.Entry<String, String>> out = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < nameValues.length; i += 2) {
            if (nameValues[i + 1] != null) {
                out.add(Map.entry(nameValues[i], nameValues[i + 1]));
            }
        }
        return out;
    }
}
