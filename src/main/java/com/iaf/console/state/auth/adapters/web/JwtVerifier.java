package com.iaf.console.state.auth.adapters.web;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * Verifies the console's internal JWTs: ES256, signed by the BFF, whose JWKS is fetched over HTTP
 * and kept for a while (and fetched again once when a token names a key id the set does not
 * hold, for a rotation). The claims: {@code iss} the BFF, {@code aud} this service (or
 * {@code console}), {@code exp} in the future, {@code sub} who, {@code acct} the account (absent
 * on a service token), {@code region}, {@code groups}, {@code roles} (the managed roles the person
 * holds in the account, which decide admin-only things such as {@code reveal=sensitive}) and {@code svc}.
 */
public final class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);
    private static final Duration SKEW = Duration.ofSeconds(30);
    private static final Duration JWKS_TTL = Duration.ofMinutes(5);
    private static final Duration REFRESH_GAP = Duration.ofSeconds(30);

    /** A token that does not verify; the message says why, the client only hears 401. */
    public static final class InvalidToken extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public InvalidToken(String message) {
            super(message);
        }
    }

    private final WebClient web;
    private final String jwksUri;
    private final String issuer;
    private final List<String> audiences;
    private final Clock clock;
    private final AtomicReference<Mono<JWKSet>> jwks = new AtomicReference<>();
    private final AtomicReference<Instant> lastRefresh = new AtomicReference<>(Instant.EPOCH);

    public JwtVerifier(WebClient web, String jwksUri, String issuer, List<String> audiences, Clock clock) {
        this.web = web;
        this.jwksUri = jwksUri;
        this.issuer = issuer;
        this.audiences = List.copyOf(audiences);
        this.clock = clock;
        this.jwks.set(fetch());
    }

    /** A verifier over a fixed key set, for tests and for a service that ships the BFF's keys. */
    public JwtVerifier(JWKSet keys, String issuer, List<String> audiences, Clock clock) {
        this.web = null;
        this.jwksUri = null;
        this.issuer = issuer;
        this.audiences = List.copyOf(audiences);
        this.clock = clock;
        this.jwks.set(Mono.just(keys));
    }

    public Mono<ConsolePrincipal> verify(String token) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            return Mono.error(new InvalidToken("not a JWS"));
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            return Mono.error(new InvalidToken("alg is not ES256"));
        }
        String kid = jwt.getHeader().getKeyID();
        return keyFor(kid, false).switchIfEmpty(Mono.defer(() -> keyFor(kid, true)))
                .switchIfEmpty(Mono.error(() -> new InvalidToken("no key '" + kid + "' in the JWKS")))
                .flatMap(key -> Mono.fromCallable(() -> check(jwt, key)));
    }

    private Mono<ECKey> keyFor(String kid, boolean refresh) {
        if (refresh) {
            if (web == null || clock.instant().isBefore(lastRefresh.get().plus(REFRESH_GAP))) {
                return Mono.empty();
            }
            lastRefresh.set(clock.instant());
            jwks.set(fetch());
        }
        return jwks.get().flatMap(set -> {
            JWK found = kid == null ? (set.getKeys().size() == 1 ? set.getKeys().get(0) : null) : set.getKeyByKeyId(kid);
            return found instanceof ECKey ec ? Mono.just(ec) : Mono.empty();
        });
    }

    private ConsolePrincipal check(SignedJWT jwt, ECKey key) throws JOSEException, ParseException {
        if (!jwt.verify(new ECDSAVerifier(key.toECPublicKey()))) {
            throw new InvalidToken("the signature does not verify");
        }
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        Instant now = clock.instant();
        if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().plus(SKEW).isBefore(now)) {
            throw new InvalidToken("expired");
        }
        if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().minus(SKEW).isAfter(now)) {
            throw new InvalidToken("not yet valid");
        }
        if (issuer != null && !issuer.equals(claims.getIssuer())) {
            throw new InvalidToken("issuer '" + claims.getIssuer() + "' is not trusted");
        }
        List<String> aud = claims.getAudience() == null ? List.of() : claims.getAudience();
        if (!audiences.isEmpty() && aud.stream().noneMatch(audiences::contains)) {
            throw new InvalidToken("audience " + aud + " is not this service");
        }
        String subject = claims.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidToken("no subject");
        }
        String acct = claims.getStringClaim("acct");
        String region = claims.getStringClaim("region");
        String svc = claims.getStringClaim("svc");
        return new ConsolePrincipal(subject, acct == null || acct.isBlank() ? null : acct, region, strings(claims.getClaim("groups")),
                strings(claims.getClaim("roles")), svc);
    }

    private static List<String> strings(Object claim) {
        List<String> values = new ArrayList<>();
        if (claim instanceof List<?> list) {
            list.forEach(x -> values.add(String.valueOf(x)));
        }
        return values;
    }

    private Mono<JWKSet> fetch() {
        return Mono.defer(() -> web.get().uri(Objects.requireNonNull(jwksUri)).retrieve().bodyToMono(String.class)
                .map(body -> {
                    try {
                        JWKSet set = JWKSet.parse(body);
                        log.info("JWKS from {}: {} key(s)", jwksUri, set.getKeys().size());
                        return set;
                    } catch (ParseException e) {
                        throw new IllegalStateException("the JWKS at " + jwksUri + " does not parse", e);
                    }
                }))
                .cache(s -> JWKS_TTL, e -> Duration.ZERO, () -> Duration.ZERO);
    }
}
