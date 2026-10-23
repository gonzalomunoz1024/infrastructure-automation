package com.iaf.console.platform.assertion;

import java.text.ParseException;
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
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The P-02 assertion a console service sends in {@code X-On-Behalf-Of}: an ES256 JWS naming the
 * console user, signed by the BFF. {@link Signer} makes one, {@link Verifier} checks one the way the
 * platform does (signature, audience, issuer, lifetime, skew); replay refusal by {@code jti} is the
 * verifier's caller's job, since it needs a store.
 */
public final class OnBehalfOfAssertions {

    /** The most an assertion may live: exp at most this long after iat. */
    public static final Duration MAX_LIFETIME = Duration.ofSeconds(60);
    /** The clock skew the platform allows. */
    public static final Duration SKEW = Duration.ofSeconds(30);

    private OnBehalfOfAssertions() {
    }

    /** The claims of an assertion, as signed or as verified. */
    public record Claims(String issuer, String audience, String subject, String name, String email, List<String> groups, String accountId,
            String region, String jti, Instant issuedAt, Instant expiresAt) {

        public Claims {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    /** The console user an assertion is made for. */
    public record User(String subject, String name, String email, List<String> groups) {

        public User {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }
    }

    /** A new ES256 key pair with a key id, generated at run time; never a committed key. */
    public static ECKey generateKey(String keyId) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(keyId).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot generate an ES256 key", e);
        }
    }

    /** Signs assertions with one private key for one issuer and audience. */
    public static final class Signer {

        private final ECKey key;
        private final ECDSASigner signer;
        private final String issuer;
        private final String audience;
        private final Clock clock;
        private final Duration lifetime;

        public Signer(ECKey privateKey, String issuer, String audience) {
            this(privateKey, issuer, audience, Clock.systemUTC(), Duration.ofSeconds(30));
        }

        public Signer(ECKey privateKey, String issuer, String audience, Clock clock, Duration lifetime) {
            if (!privateKey.isPrivate()) {
                throw new IllegalArgumentException("the signing key must hold the private part");
            }
            if (lifetime.compareTo(MAX_LIFETIME) > 0 || lifetime.isNegative() || lifetime.isZero()) {
                throw new IllegalArgumentException("an assertion lives at most " + MAX_LIFETIME.toSeconds() + " seconds");
            }
            this.key = privateKey;
            try {
                this.signer = new ECDSASigner(privateKey);
            } catch (JOSEException e) {
                throw new IllegalArgumentException("not an ES256 signing key", e);
            }
            this.issuer = issuer;
            this.audience = audience;
            this.clock = clock;
            this.lifetime = lifetime;
        }

        /** The public half, as the platform's JWKS would publish it. */
        public ECKey publicKey() {
            return key.toPublicJWK();
        }

        public String keyId() {
            return key.getKeyID();
        }

        public String issuer() {
            return issuer;
        }

        public String audience() {
            return audience;
        }

        /** A compact JWS for this user acting in this account and region, with a fresh jti. */
        public String sign(User user, String accountId, String region) {
            return sign(user, accountId, region, UUID.randomUUID().toString());
        }

        public String sign(User user, String accountId, String region, String jti) {
            Instant now = clock.instant();
            return sign(new Claims(issuer, audience, user.subject(), user.name(), user.email(), user.groups(), accountId, region, jti, now,
                    now.plus(lifetime)));
        }

        /** Signs exactly these claims; tests use it for expired or oversized assertions. */
        public String sign(Claims claims) {
            JWTClaimsSet set = new JWTClaimsSet.Builder()
                    .issuer(claims.issuer())
                    .audience(claims.audience())
                    .subject(claims.subject())
                    .claim("name", claims.name())
                    .claim("email", claims.email())
                    .claim("groups", claims.groups())
                    .claim("acct", claims.accountId())
                    .claim("region", claims.region())
                    .jwtID(claims.jti())
                    .issueTime(Date.from(claims.issuedAt()))
                    .expirationTime(Date.from(claims.expiresAt()))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), set);
            try {
                jwt.sign(signer);
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the assertion", e);
            }
            return jwt.serialize();
        }
    }

    /** Why an assertion was refused; the platform answers 401 for all of these. */
    public static final class InvalidAssertionException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        public InvalidAssertionException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** One of malformed, signature, key, issuer, audience, expired, premature, lifetime, claims. */
        public String reason() {
            return reason;
        }
    }

    /** Verifies assertions against a set of public keys, the way the platform does. */
    public static final class Verifier {

        private volatile JWKSet trusted;
        private final String issuer;
        private final String audience;
        private final Clock clock;

        public Verifier(JWKSet trusted, String issuer, String audience) {
            this(trusted, issuer, audience, Clock.systemUTC());
        }

        public Verifier(JWKSet trusted, String issuer, String audience, Clock clock) {
            this.trusted = trusted == null ? new JWKSet() : trusted;
            this.issuer = issuer;
            this.audience = audience;
            this.clock = clock;
        }

        /** A verifier of one signer's assertions, for tests. */
        public static Verifier of(Signer signer) {
            return new Verifier(new JWKSet(signer.publicKey()), signer.issuer(), signer.audience());
        }

        /** Replaces the trusted keys (a JWKS refresh, a rotation). */
        public void trust(JWKSet keys) {
            this.trusted = keys == null ? new JWKSet() : keys;
        }

        public JWKSet trusted() {
            return trusted;
        }

        public String audience() {
            return audience;
        }

        /** The claims of a valid assertion; an {@link InvalidAssertionException} otherwise. */
        public Claims verify(String compactJws) {
            if (compactJws == null || compactJws.isBlank()) {
                throw new InvalidAssertionException("malformed", "the assertion is empty");
            }
            SignedJWT jwt;
            try {
                jwt = SignedJWT.parse(compactJws.trim());
            } catch (ParseException e) {
                throw new InvalidAssertionException("malformed", "the assertion is not a compact JWS");
            }
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
                throw new InvalidAssertionException("signature", "the assertion is not signed with ES256");
            }
            ECKey key = keyFor(jwt.getHeader().getKeyID());
            try {
                if (!jwt.verify(new ECDSAVerifier(key.toECPublicKey()))) {
                    throw new InvalidAssertionException("signature", "the assertion's signature does not verify");
                }
            } catch (JOSEException e) {
                throw new InvalidAssertionException("signature", "the assertion's signature cannot be checked");
            }
            JWTClaimsSet set;
            try {
                set = jwt.getJWTClaimsSet();
            } catch (ParseException e) {
                throw new InvalidAssertionException("claims", "the assertion's claims cannot be read");
            }
            if (issuer != null && !issuer.equals(set.getIssuer())) {
                throw new InvalidAssertionException("issuer", "the assertion names another issuer");
            }
            if (set.getAudience() == null || !set.getAudience().contains(audience)) {
                throw new InvalidAssertionException("audience", "the assertion is for another audience");
            }
            if (set.getIssueTime() == null || set.getExpirationTime() == null) {
                throw new InvalidAssertionException("claims", "the assertion needs iat and exp");
            }
            Instant iat = set.getIssueTime().toInstant();
            Instant exp = set.getExpirationTime().toInstant();
            Instant now = clock.instant();
            if (Duration.between(iat, exp).compareTo(MAX_LIFETIME) > 0) {
                throw new InvalidAssertionException("lifetime", "the assertion lives longer than " + MAX_LIFETIME.toSeconds() + " seconds");
            }
            if (now.isAfter(exp.plus(SKEW))) {
                throw new InvalidAssertionException("expired", "the assertion has expired");
            }
            if (now.isBefore(iat.minus(SKEW))) {
                throw new InvalidAssertionException("premature", "the assertion is not valid yet");
            }
            String subject = set.getSubject();
            String account = stringClaim(set, "acct");
            String jti = set.getJWTID();
            if (subject == null || subject.isBlank() || account == null || account.isBlank() || jti == null || jti.isBlank()) {
                throw new InvalidAssertionException("claims", "the assertion needs sub, acct and jti");
            }
            List<String> groups;
            try {
                groups = set.getStringListClaim("groups");
            } catch (ParseException e) {
                groups = List.of();
            }
            return new Claims(set.getIssuer(), audience, subject, stringClaim(set, "name"), stringClaim(set, "email"), groups, account,
                    stringClaim(set, "region"), jti, iat, exp);
        }

        private static String stringClaim(JWTClaimsSet set, String name) {
            Object value = set.getClaim(name);
            return value == null ? null : value.toString();
        }

        private ECKey keyFor(String keyId) {
            JWKSet keys = trusted;
            JWK byId = keyId == null ? null : keys.getKeyByKeyId(keyId);
            if (byId instanceof ECKey ec) {
                return ec;
            }
            if (keyId == null) {
                for (JWK jwk : keys.getKeys()) {
                    if (jwk instanceof ECKey ec) {
                        return ec;
                    }
                }
            }
            throw new InvalidAssertionException("key", "the assertion is signed with a key the platform does not trust");
        }
    }
}
