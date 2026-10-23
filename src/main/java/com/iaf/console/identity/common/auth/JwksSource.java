package com.iaf.console.identity.common.auth;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;

import reactor.core.publisher.Mono;

/**
 * The public keys internal JWTs are verified against: a set given inline, or fetched from the
 * BFF's JWKS endpoint and refreshed on a timer and whenever an unknown key id turns up (at most
 * once every ten seconds, so a flood of bad tokens cannot hammer the BFF).
 */
public final class JwksSource {

    private static final Logger log = LoggerFactory.getLogger(JwksSource.class);
    private static final Duration MIN_REFRESH_GAP = Duration.ofSeconds(10);

    private final WebClient web;
    private final String uri;
    private final Duration refreshEvery;
    private final Clock clock;
    private volatile JWKSet keys;
    private volatile Instant fetchedAt = Instant.EPOCH;

    public JwksSource(JWKSet inline, String uri, Duration refreshEvery, WebClient.Builder webClient, Clock clock) {
        this.keys = inline == null ? new JWKSet() : inline;
        this.uri = uri;
        this.refreshEvery = refreshEvery;
        this.web = webClient == null ? null : webClient.build();
        this.clock = clock;
        if (inline != null) {
            this.fetchedAt = clock.instant();
        }
    }

    /** A source of fixed keys, for tests and the demo. */
    public static JwksSource of(JWKSet keys) {
        return new JwksSource(keys, null, Duration.ofDays(365), null, Clock.systemUTC());
    }

    /** The key with this id (or the only one when the token names none); empty when no trusted key fits. */
    public Mono<JWK> key(String keyId) {
        JWK known = find(keys, keyId);
        boolean stale = uri != null && clock.instant().isAfter(fetchedAt.plus(refreshEvery));
        if (known != null && !stale) {
            return Mono.just(known);
        }
        if (uri == null || web == null) {
            return Mono.justOrEmpty(known);
        }
        if (clock.instant().isBefore(fetchedAt.plus(MIN_REFRESH_GAP))) {
            return Mono.justOrEmpty(known);
        }
        return refresh().map(set -> find(set, keyId)).switchIfEmpty(Mono.justOrEmpty(known));
    }

    /** Fetches the JWKS now; the previous keys stay when the fetch fails. */
    public Mono<JWKSet> refresh() {
        fetchedAt = clock.instant();
        return web.get().uri(uri).retrieve().bodyToMono(String.class)
                .map(body -> {
                    try {
                        return JWKSet.parse(body);
                    } catch (ParseException e) {
                        throw new IllegalStateException("the JWKS at " + uri + " is not a JWK set", e);
                    }
                })
                .doOnNext(set -> keys = set)
                .onErrorResume(e -> {
                    log.warn("the JWKS at {} could not be fetched: {}", uri, e.toString());
                    return Mono.empty();
                });
    }

    /** Replaces the keys, for tests and the demo. */
    public void trust(JWKSet set) {
        this.keys = set == null ? new JWKSet() : set;
        this.fetchedAt = clock.instant();
    }

    public JWKSet keys() {
        return keys;
    }

    private static JWK find(JWKSet set, String keyId) {
        if (set == null) {
            return null;
        }
        if (keyId != null) {
            return set.getKeyByKeyId(keyId);
        }
        return set.getKeys().isEmpty() ? null : set.getKeys().get(0);
    }
}
