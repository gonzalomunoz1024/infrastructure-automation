package com.iaf.console.search.index.adapters.state;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.http.HttpHeaders;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * How this service identifies itself to another console service: a token it was given, a token
 * it signs itself with the BFF's key when it holds a copy (the service token of the contract,
 * {@code sub} and {@code svc} this service, no {@code acct}), or, in the demo, the dev header
 * {@code X-Console-Service}.
 */
public interface ServiceCredentials {

    /** The service id this service calls as. */
    String SERVICE = "console-search-indexer";

    void apply(HttpHeaders headers);

    static ServiceCredentials bearer(String token) {
        return headers -> headers.setBearerAuth(token);
    }

    static ServiceCredentials devHeaders() {
        return headers -> headers.set("X-Console-Service", SERVICE);
    }

    /** Tokens signed with an EC key (a JWK as JSON, the BFF's signing key), each good for a minute and reused for most of it. */
    static ServiceCredentials signed(String jwkJson, String issuer, List<String> audiences, Clock clock) {
        ECKey key;
        try {
            key = ECKey.parse(jwkJson);
        } catch (ParseException e) {
            throw new IllegalArgumentException("the signing key is not an EC JWK", e);
        }
        if (key.getD() == null) {
            throw new IllegalArgumentException("the signing key has no private part");
        }
        return new Signed(key, issuer, audiences, clock);
    }

    /** The self-signed service token. */
    final class Signed implements ServiceCredentials {
        private static final Duration TTL = Duration.ofSeconds(60);
        private static final Duration REUSE = Duration.ofSeconds(40);

        private final ECKey key;
        private final String issuer;
        private final List<String> audiences;
        private final Clock clock;
        private final AtomicReference<Minted> current = new AtomicReference<>();

        private record Minted(String token, Instant mintedAt) {
        }

        Signed(ECKey key, String issuer, List<String> audiences, Clock clock) {
            this.key = key;
            this.issuer = issuer;
            this.audiences = List.copyOf(audiences);
            this.clock = clock;
        }

        @Override
        public void apply(HttpHeaders headers) {
            headers.setBearerAuth(token());
        }

        String token() {
            Instant now = clock.instant();
            Minted m = current.get();
            if (m != null && m.mintedAt().plus(REUSE).isAfter(now)) {
                return m.token();
            }
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audiences).subject("svc:" + SERVICE).claim("svc", SERVICE)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(TTL))).build();
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), claims);
                jwt.sign(new ECDSASigner(key));
                String token = jwt.serialize();
                current.set(new Minted(token, now));
                return token;
            } catch (JOSEException e) {
                throw new IllegalStateException("the service token could not be signed", e);
            }
        }
    }
}
