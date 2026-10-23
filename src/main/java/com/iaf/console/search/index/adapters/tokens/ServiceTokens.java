package com.iaf.console.search.index.adapters.tokens;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Service tokens from the BFF ({@code POST /internal/v1/token}, {@code issueServiceToken} in
 * {@code contracts/openapi/console-bff.yaml}): OAuth 2.0 client credentials with a JWT client
 * assertion, one token per callee, so a token one service sees is no good at another. A token is
 * reused for 80% of its life; then the next call asks again (calls at the same moment share the
 * request), and while the BFF cannot answer, the old token serves until shortly before it ends. A
 * request has a time limit, so a BFF that never answers holds up no call for longer.
 */
public final class ServiceTokens {

    static final String GRANT_TYPE = "client_credentials";
    static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private static final Logger log = LoggerFactory.getLogger(ServiceTokens.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double REUSE_FOR = 0.8;
    /** A token this close to its end is no longer sent: the callee's clock may run ahead. */
    private static final Duration SKEW = Duration.ofSeconds(10);

    private final WebClient web;
    private final URI tokenUrl;
    private final ClientAssertion assertion;
    private final String clientId;
    private final Duration timeout;
    private final Clock clock;
    private final ConcurrentMap<String, Slot> slots = new ConcurrentHashMap<>();

    /** {@code timeout} is how long one request to the BFF may take. */
    public ServiceTokens(WebClient web, URI tokenUrl, ClientAssertion assertion, String clientId, Duration timeout, Clock clock) {
        this.web = web;
        this.tokenUrl = tokenUrl;
        this.assertion = assertion;
        this.clientId = clientId;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** A token for one callee, whose service id is the audience. */
    public Mono<String> token(String audience) {
        return slots.computeIfAbsent(audience, Slot::new).token();
    }

    /** Forgets a token the callee answered 401 to, so the next call asks for a new one. */
    public void refused(String audience, String token) {
        Slot slot = slots.get(audience);
        if (slot != null) {
            slot.forget(token);
        }
    }

    /** What the BFF answered when it gave no token: the RFC 6749 error, or the status alone. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;
        private final String error;

        public Refused(int status, String error, String description) {
            super("the BFF gave no service token: " + status + (error.isEmpty() ? "" : " " + error) + (description.isEmpty() ? "" : " (" + description + ")"));
            this.status = status;
            this.error = error;
        }

        public int status() {
            return status;
        }

        public String error() {
            return error;
        }
    }

    private record Issued(String token, Instant reuseUntil, Instant expiresAt) {
    }

    /** One callee's token and the request that renews it. */
    private final class Slot {
        private final String audience;
        private Issued issued;
        private Mono<Issued> asking;

        Slot(String audience) {
            this.audience = audience;
        }

        Mono<String> token() {
            return Mono.defer(() -> {
                Issued held;
                Mono<Issued> request;
                synchronized (this) {
                    held = issued;
                    if (held != null && clock.instant().isBefore(held.reuseUntil())) {
                        return Mono.just(held.token());
                    }
                    if (asking == null) {
                        // cleared before any caller hears the outcome, so a later call never joins a finished request
                        asking = ask(audience).doOnNext(this::keep).doOnError(e -> asked()).doOnCancel(this::asked).cache();
                    }
                    request = asking;
                }
                return request.map(Issued::token).onErrorResume(e -> {
                    if (held != null && clock.instant().isBefore(held.expiresAt().minus(SKEW))) {
                        log.warn("no new service token for {} ({}); the current one serves until it ends", audience, e.getMessage());
                        return Mono.just(held.token());
                    }
                    return Mono.error(e);
                });
            });
        }

        private synchronized void keep(Issued fresh) {
            issued = fresh;
            asking = null;
        }

        private synchronized void asked() {
            asking = null;
        }

        synchronized void forget(String token) {
            if (issued != null && issued.token().equals(token)) {
                issued = null;
            }
        }
    }

    /**
     * One request, given up after the timeout (the callers share it, so it must end); a retry signs
     * a new assertion, since the BFF takes each jti once.
     */
    private Mono<Issued> ask(String audience) {
        return assertion.next().flatMap(jwt -> {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("grant_type", GRANT_TYPE);
            form.add("client_assertion_type", ASSERTION_TYPE);
            form.add("client_assertion", jwt);
            form.add("client_id", clientId);
            form.add("audience", audience);
            return web.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED).accept(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromFormData(form))
                    .exchangeToMono(r -> r.statusCode().is2xxSuccessful() ? r.bodyToMono(JsonNode.class).map(this::issued) : refusal(r));
        })
                .timeout(timeout)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2))
                        .filter(e -> !(e instanceof Refused refused) || refused.status() >= 500)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .doOnNext(i -> log.debug("a service token for {} until {}", audience, i.expiresAt()));
    }

    private Issued issued(JsonNode body) {
        String token = body.path("access_token").asText("");
        long seconds = body.path("expires_in").asLong(0);
        if (token.isBlank() || !"bearer".equalsIgnoreCase(body.path("token_type").asText("")) || seconds <= 0) {
            throw new Refused(200, "invalid_response", "no bearer access_token with a positive expires_in");
        }
        Instant now = clock.instant();
        return new Issued(token, now.plusMillis(Math.round(seconds * 1000 * REUSE_FOR)), now.plusSeconds(seconds));
    }

    private static Mono<Issued> refusal(ClientResponse r) {
        return r.bodyToMono(String.class).defaultIfEmpty("").flatMap(text -> {
            String error = "";
            String description = "";
            try {
                JsonNode body = JSON.readTree(text);
                if (body != null) {
                    error = body.path("error").asText("");
                    description = body.path("error_description").asText("");
                }
            } catch (Exception notJson) {
                // a proxy's page, say: the status says enough
            }
            return Mono.error(new Refused(r.statusCode().value(), error, description));
        });
    }
}
