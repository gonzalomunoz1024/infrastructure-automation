package com.iaf.console.search.index.adapters.tokens;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * How this service proves who it is when it asks the BFF for a service token (hub decision 23,
 * RFC 7523): the pod's projected Kubernetes service-account token in a cluster, or a JWT it signs
 * with its own registered key anywhere else. Each request gets a fresh one.
 */
@FunctionalInterface
public interface ClientAssertion {

    Mono<String> next();

    /**
     * The projected service-account token at a path, read again for every request: the kubelet
     * rewrites the file before the token expires.
     */
    static ClientAssertion projectedToken(Path file) {
        return () -> Mono.fromCallable(() -> {
            try {
                String token = Files.readString(file).trim();
                if (token.isEmpty()) {
                    throw new IllegalStateException("the service-account token at " + file + " is empty");
                }
                return token;
            } catch (IOException e) {
                throw new UncheckedIOException("the service-account token at " + file + " cannot be read", e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * JWTs signed with this service's own EC key (a private JWK with a {@code kid}, registered with
     * the BFF): {@code iss} and {@code sub} the client, {@code aud} the BFF, a minute long, a new
     * {@code jti} each time.
     */
    static ClientAssertion signed(String privateJwk, String clientId, String audience, Clock clock) {
        ECKey key;
        try {
            key = ECKey.parse(privateJwk);
        } catch (ParseException e) {
            throw new IllegalArgumentException("the client key is not an EC JWK", e);
        }
        if (!key.isPrivate()) {
            throw new IllegalArgumentException("the client key has no private part");
        }
        if (key.getKeyID() == null || key.getKeyID().isBlank()) {
            throw new IllegalArgumentException("the client key has no kid: the BFF finds the registered key by it");
        }
        JWSAlgorithm algorithm;
        if (Curve.P_256.equals(key.getCurve())) {
            algorithm = JWSAlgorithm.ES256;
        } else if (Curve.P_384.equals(key.getCurve())) {
            algorithm = JWSAlgorithm.ES384;
        } else {
            throw new IllegalArgumentException("the client key's curve is " + key.getCurve() + "; the BFF takes P-256 (ES256) or P-384 (ES384)");
        }
        return new Signed(key, algorithm, clientId, audience, clock);
    }

    /** The self-signed assertion. */
    final class Signed implements ClientAssertion {
        static final Duration LIFETIME = Duration.ofSeconds(60);

        private final ECKey key;
        private final JWSAlgorithm algorithm;
        private final String clientId;
        private final String audience;
        private final Clock clock;

        Signed(ECKey key, JWSAlgorithm algorithm, String clientId, String audience, Clock clock) {
            this.key = key;
            this.algorithm = algorithm;
            this.clientId = clientId;
            this.audience = audience;
            this.clock = clock;
        }

        @Override
        public Mono<String> next() {
            return Mono.fromCallable(this::sign);
        }

        private String sign() throws JOSEException {
            Instant now = clock.instant();
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(clientId).subject(clientId).audience(audience).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(LIFETIME))).jwtID(UUID.randomUUID().toString()).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new ECDSASigner(key));
            return jwt.serialize();
        }
    }
}
