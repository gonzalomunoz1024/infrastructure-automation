package com.iaf.console.drift.drift.adapters.platform;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.drift.drift.ports.outbound.PlatformCredentialsPort;

import reactor.core.publisher.Mono;

/**
 * The identity service's answer to {@code GET /v1/accounts/{accountId}/platform-credentials} (hub
 * decision 18): with {@code source: local} the token itself, kept for a while; with {@code source:
 * vault} the Vault path, which this service does not read yet (the account's revert then needs a
 * configured token under {@code console.platform.account-tokens}).
 */
public final class IdentityPlatformCredentials implements PlatformCredentialsPort {

    private static final Logger log = LoggerFactory.getLogger(IdentityPlatformCredentials.class);
    private static final Duration KEEP = Duration.ofMinutes(5);

    private record Cached(String token, Instant until) {
    }

    private final WebClient web;
    private final String baseUrl;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** {@code credentials} name this service to the identity service: a BFF service token (hub decision 23), or the demos' dev header. */
    public IdentityPlatformCredentials(WebClient.Builder builder, String baseUrl, ExchangeFilterFunction credentials, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.web = builder.clone().defaultHeader(HttpHeaders.USER_AGENT, "console-drift-service")
                .filter(credentials)
                .filter((request, next) -> next.exchange(request).timeout(timeout))
                .build();
    }

    @Override
    public Mono<String> tokenFor(String accountId) {
        Cached c = cache.get(accountId);
        if (c != null && c.until().isAfter(Instant.now())) {
            return Mono.just(c.token());
        }
        return web.get().uri(URI.create(baseUrl + "/v1/accounts/" + accountId + "/platform-credentials")).retrieve().bodyToMono(JsonNode.class)
                .flatMap(body -> {
                    if ("local".equals(body.path("source").asText()) && body.hasNonNull("token")) {
                        String token = body.get("token").asText();
                        cache.put(accountId, new Cached(token, Instant.now().plus(KEEP)));
                        return Mono.just(token);
                    }
                    log.warn("account {}: the identity service keeps the platform credential in {} ({}), which this service cannot read", accountId,
                            body.path("source").asText(), body.path("vaultPath").asText());
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("account {}: the identity service did not answer the platform credential: {}", accountId, e.toString());
                    return Mono.empty();
                });
    }
}
