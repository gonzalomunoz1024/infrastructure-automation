package com.iaf.console.cost.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Internal JWTs as the BFF signs them (ES256, {@code kid}), with a key made for the test run: a
 * person's ({@code acct}, {@code roles}, {@code svc console-bff}) and a console service's own
 * ({@code sub} and {@code svc} its id, no {@code acct}), both for exactly this service as hub
 * decision 35 has it, and any other shape through {@link #sign}.
 */
public final class Tokens {

    public static final String ISSUER = "console-bff";
    public static final String AUDIENCE = "console-cost-service";

    private final ECKey key;
    private final Clock clock;

    public Tokens(Clock clock) {
        try {
            this.key = new ECKeyGenerator(Curve.P_256).keyID("bff-test-" + UUID.randomUUID()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        this.clock = clock;
    }

    /** The BFF's JWKS: the public key only. */
    public JWKSet jwks() {
        return new JWKSet(key.toPublicJWK());
    }

    /** A person's token in an account, with the managed roles they hold there. */
    public String person(String userId, String accountId, String... roles) {
        return sign(new JWTClaimsSet.Builder().subject(userId).claim("acct", accountId).claim("region", "global").claim("groups", List.of())
                .claim("roles", List.of(roles)).claim("svc", ISSUER));
    }

    /** A console service's own token (hub decision 23): no account, sub and svc its id. */
    public String service(String serviceId) {
        return sign(new JWTClaimsSet.Builder().subject(serviceId).claim("svc", serviceId).claim("groups", List.of()).claim("roles", List.of()));
    }

    /**
     * These claims, with the BFF as issuer, exactly this service as audience and a minute's life
     * unless the builder names them ({@code aud} set to an empty list leaves it out).
     */
    public String sign(JWTClaimsSet.Builder claims) {
        Instant now = clock.instant();
        JWTClaimsSet set = claims.build();
        JWTClaimsSet.Builder full = new JWTClaimsSet.Builder(set).jwtID(UUID.randomUUID().toString());
        if (!set.getClaims().containsKey("iss")) {
            full.issuer(ISSUER);
        }
        if (!set.getClaims().containsKey("aud")) {
            full.audience(AUDIENCE);
        }
        if (!set.getClaims().containsKey("iat")) {
            full.issueTime(Date.from(now));
        }
        if (!set.getClaims().containsKey("exp")) {
            full.expirationTime(Date.from(now.plus(Duration.ofSeconds(60))));
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), full.build());
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }
}
