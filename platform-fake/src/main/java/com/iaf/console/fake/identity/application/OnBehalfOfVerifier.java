package com.iaf.console.fake.identity.application;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.iaf.console.fake.common.ApiException;
import com.iaf.console.fake.common.FakeClock;
import com.iaf.console.fake.identity.domain.Delegation;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Verifies X-On-Behalf-Of assertions (P-02): an ES256 JWS from a trusted key, for this platform,
 * at most a minute long, never seen before. The trusted keys come from configuration, from a
 * caller of {@link #trust(JWK)}, or from a key pair the fake generates for demos.
 */
public final class OnBehalfOfVerifier {

    /** The longest an assertion may live. */
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    /** The clock skew the verifier tolerates. */
    public static final Duration SKEW = Duration.ofSeconds(30);

    private final FakeClock clock;
    private final String issuer;
    private final String audience;
    private final List<JWK> trusted = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final ECKey demoKey;

    public OnBehalfOfVerifier(FakeClock clock, String issuer, String audience, JWKSet configured, boolean generateDemoKey) {
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        if (configured != null) {
            trusted.addAll(configured.getKeys());
        }
        ECKey generated = null;
        if (generateDemoKey) {
            try {
                generated = new ECKeyGenerator(Curve.P_256).keyID("platform-fake-demo").generate();
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot generate the demo signing key", e);
            }
            trusted.add(generated.toPublicJWK());
        }
        this.demoKey = generated;
    }

    public String issuer() {
        return issuer;
    }

    public String audience() {
        return audience;
    }

    /** Trusts one more public key. */
    public void trust(JWK key) {
        trusted.add(key.toPublicJWK());
    }

    public void trust(JWKSet keys) {
        keys.getKeys().forEach(this::trust);
    }

    /** The public keys the verifier trusts. */
    public JWKSet trusted() {
        return new JWKSet(new ArrayList<>(trusted));
    }

    /** The demo signing key, private part included, when the fake generated one; null otherwise. */
    public ECKey demoKey() {
        return demoKey;
    }

    /**
     * Verifies an assertion and answers the delegation. A malformed, badly signed, foreign, expired,
     * premature or replayed assertion is refused with 401.
     */
    public Delegation verify(String compact) {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(compact);
        } catch (ParseException e) {
            throw ApiException.unauthorized("on-behalf-of assertion is malformed");
        }
        if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
            throw ApiException.unauthorized("on-behalf-of assertion must be signed with ES256");
        }
        if (!signatureHolds(jwt)) {
            throw ApiException.unauthorized("on-behalf-of assertion has a bad signature");
        }
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw ApiException.unauthorized("on-behalf-of assertion has bad claims");
        }
        if (issuer != null && !issuer.equals(claims.getIssuer())) {
            throw ApiException.unauthorized("on-behalf-of assertion has a wrong issuer");
        }
        if (audience != null && (claims.getAudience() == null || !claims.getAudience().contains(audience))) {
            throw ApiException.unauthorized("on-behalf-of assertion has a wrong audience");
        }
        Date issuedAt = claims.getIssueTime();
        Date expiresAt = claims.getExpirationTime();
        if (issuedAt == null || expiresAt == null) {
            throw ApiException.unauthorized("on-behalf-of assertion needs iat and exp");
        }
        Instant iat = issuedAt.toInstant();
        Instant exp = expiresAt.toInstant();
        if (Duration.between(iat, exp).compareTo(MAX_LIFETIME) > 0) {
            throw ApiException.unauthorized("on-behalf-of assertion lives longer than a minute");
        }
        Instant now = clock.now();
        if (now.isAfter(exp.plus(SKEW))) {
            throw ApiException.unauthorized("on-behalf-of assertion has expired");
        }
        if (now.isBefore(iat.minus(SKEW))) {
            throw ApiException.unauthorized("on-behalf-of assertion is not valid yet");
        }
        String subject = claims.getSubject();
        String account = text(claims, "acct");
        String jti = claims.getJWTID();
        if (subject == null || account == null || jti == null) {
            throw ApiException.unauthorized("on-behalf-of assertion needs sub, acct and jti");
        }
        pruneSeen(now);
        if (seen.putIfAbsent(jti, exp.plus(SKEW)) != null) {
            throw ApiException.unauthorized("on-behalf-of assertion was already used");
        }
        List<String> groups = new ArrayList<>();
        Object groupsClaim = claims.getClaim("groups");
        if (groupsClaim instanceof List<?> list) {
            for (Object group : list) {
                groups.add(String.valueOf(group));
            }
        }
        return new Delegation(subject, text(claims, "name"), text(claims, "email"), groups, account, text(claims, "region"), jti);
    }

    private boolean signatureHolds(SignedJWT jwt) {
        String kid = jwt.getHeader().getKeyID();
        for (JWK key : trusted) {
            if (!(key instanceof ECKey ecKey)) {
                continue;
            }
            if (kid != null && key.getKeyID() != null && !kid.equals(key.getKeyID())) {
                continue;
            }
            try {
                if (jwt.verify(new ECDSAVerifier(ecKey.toECPublicKey()))) {
                    return true;
                }
            } catch (JOSEException e) {
                // try the next key
            }
        }
        return false;
    }

    private void pruneSeen(Instant now) {
        if (seen.size() > 10_000) {
            seen.entrySet().removeIf(entry -> entry.getValue().isBefore(now));
        }
    }

    private static String text(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }
}
