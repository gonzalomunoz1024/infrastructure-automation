package com.iaf.console.bff.platform.application;

import java.time.Duration;

import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iaf.console.bff.platform.domain.PlatformCredential;
import com.iaf.console.bff.platform.ports.outbound.PlatformCredentialsPort;
import com.iaf.console.platform.client.PlatformClient;
import com.iaf.console.platform.client.PlatformPort;
import com.iaf.console.kit.scope.AccountScope;

import reactor.core.publisher.Mono;

/**
 * One platform client per console account, built with that account's service account token and
 * kept for a while, plus the console-internal client for content and the feed. Every call to the
 * platform for a session goes through the session's account client, so the platform's own
 * caller-scoped reads (P-18) are the first fence.
 */
public class PlatformClients {

    private final PlatformCredentialsPort credentials;
    private final PlatformBaseUrl baseUrl;
    private final PlatformProperties properties;
    private final WebClient.Builder webClientBuilder;
    private final Cache<String, PlatformPort> clients;
    private volatile Mono<PlatformPort> internal;

    public PlatformClients(PlatformCredentialsPort credentials, PlatformBaseUrl baseUrl, PlatformProperties properties, WebClient.Builder webClientBuilder) {
        this.credentials = credentials;
        this.baseUrl = baseUrl;
        this.properties = properties;
        this.webClientBuilder = webClientBuilder;
        this.clients = Caffeine.newBuilder().expireAfterWrite(properties.clientTtl()).maximumSize(10_000).build();
    }

    /** The client acting as the scope's account. */
    public Mono<PlatformPort> forAccount(AccountScope scope) {
        PlatformPort cached = clients.getIfPresent(scope.accountId());
        if (cached != null) {
            return Mono.just(cached);
        }
        return credentials.findCredential(scope).map(credential -> {
            PlatformPort client = build(credential);
            clients.put(scope.accountId(), client);
            return client;
        });
    }

    /** The client acting as the console itself. */
    public Mono<PlatformPort> internal() {
        Mono<PlatformPort> cached = internal;
        if (cached == null) {
            cached = credentials.findInternalCredential().map(this::build).cache(Duration.ofHours(1));
            internal = cached;
        }
        return cached;
    }

    /** Forgets an account's client, for a rotated token. */
    public void forget(String accountId) {
        clients.invalidate(accountId);
    }

    private PlatformPort build(PlatformCredential credential) {
        return PlatformClient.builder()
                .baseUrl(baseUrl.get())
                .bearerToken(credential.token())
                .webClientBuilder(webClientBuilder.clone())
                .responseTimeout(properties.timeout())
                .userAgent("console-bff")
                .build();
    }
}
