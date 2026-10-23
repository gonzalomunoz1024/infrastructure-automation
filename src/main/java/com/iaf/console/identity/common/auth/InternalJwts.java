package com.iaf.console.identity.common.auth;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.iaf.console.kit.scope.AccountScope;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * The console's internal JWT, as the BFF signs it (ES256, {@code kid} in the header, verified against
 * its JWKS): {@code iss}, {@code aud}, {@code sub} (the console user id, or the service id on a
 * service token), {@code acct} (the account a user acts in; absent on a service token),
 * {@code region}, {@code groups} (the person's group names there), {@code roles} (the managed role
 * names they hold there, from the membership this service serves), {@code svc} (the calling
 * service), {@code iat}, {@code exp} and optionally {@code name} and {@code email}. {@link Verifier}
 * checks one against the JWKS, {@link Signer} makes one for tests and the demo.
 */
public final class InternalJwts {

    private InternalJwts() {
    }

    /** Verifies tokens: signature against the trusted keys, then issuer, audience, lifetime and the claims a caller needs. */
    public static final class Verifier {

        private final JwksSource keys;
        private final AuthProperties properties;
        private final Clock clock;

        public Verifier(JwksSource keys, AuthProperties properties, Clock clock) {
            this.keys = keys;
            this.properties = properties;
            this.clock = clock;
        }

        /** The caller a token names; an {@link UnauthenticatedException} for anything that does not verify. */
        public Mono<Caller> verify(String token) {
            SignedJWT jwt;
            try {
                jwt = SignedJWT.parse(token);
            } catch (ParseException e) {
                return Mono.error(new UnauthenticatedException("the token is not a compact JWS"));
            }
            if (!JWSAlgorithm.ES256.equals(jwt.getHeader().getAlgorithm())) {
                return Mono.error(new UnauthenticatedException("the token is not signed with ES256"));
            }
            return keys.key(jwt.getHeader().getKeyID())
                    .switchIfEmpty(Mono.error(new UnauthenticatedException("the token is signed with a key this service does not trust")))
                    .map(key -> caller(jwt, key));
        }

        private Caller caller(SignedJWT jwt, JWK key) {
            if (!(key instanceof ECKey ec)) {
                throw new UnauthenticatedException("the trusted key is not an EC key");
            }
            try {
                if (!jwt.verify(new ECDSAVerifier(ec.toECPublicKey()))) {
                    throw new UnauthenticatedException("the token's signature does not verify");
                }
            } catch (JOSEException e) {
                throw new UnauthenticatedException("the token's signature cannot be checked");
            }
            JWTClaimsSet set;
            try {
                set = jwt.getJWTClaimsSet();
            } catch (ParseException e) {
                throw new UnauthenticatedException("the token's claims cannot be read");
            }
            if (!properties.issuer().equals(set.getIssuer())) {
                throw new UnauthenticatedException("the token names another issuer");
            }
            List<String> audience = set.getAudience();
            if (audience == null || audience.stream().noneMatch(properties.audiences()::contains)) {
                throw new UnauthenticatedException("the token is for another audience");
            }
            if (set.getIssueTime() == null || set.getExpirationTime() == null) {
                throw new UnauthenticatedException("the token needs iat and exp");
            }
            Instant iat = set.getIssueTime().toInstant();
            Instant exp = set.getExpirationTime().toInstant();
            Instant now = clock.instant();
            if (Duration.between(iat, exp).compareTo(properties.maxLifetime()) > 0) {
                throw new UnauthenticatedException("the token lives longer than allowed");
            }
            if (now.isAfter(exp.plus(properties.skew()))) {
                throw new UnauthenticatedException("the token has expired");
            }
            if (now.isBefore(iat.minus(properties.skew()))) {
                throw new UnauthenticatedException("the token is not valid yet");
            }
            String subject = set.getSubject();
            String account = text(set, "acct");
            String service = text(set, "svc");
            String region = text(set, "region");
            if (subject == null || subject.isBlank()) {
                throw new UnauthenticatedException("the token needs a subject");
            }
            if (account == null) {
                if (service == null || service.isBlank()) {
                    throw new UnauthenticatedException("a token without an account needs svc");
                }
                return Caller.internal(service, region);
            }
            if (!AccountScope.isAccountId(account)) {
                throw new UnauthenticatedException("the token's acct is not an account id");
            }
            return Caller.user(subject, account, region, strings(set, "groups"), strings(set, "roles"), service, text(set, "name"), text(set, "email"));
        }

        /** A string-list claim; empty when absent or not a list of strings. */
        private static List<String> strings(JWTClaimsSet set, String claim) {
            try {
                List<String> values = set.getStringListClaim(claim);
                return values == null ? List.of() : values;
            } catch (ParseException e) {
                return List.of();
            }
        }

        private static String text(JWTClaimsSet set, String claim) {
            Object value = set.getClaim(claim);
            return value == null ? null : value.toString();
        }
    }

    /** Signs tokens the way the BFF does, for tests and the demo profile; never a committed key. */
    public static final class Signer {

        private final ECKey key;
        private final ECDSASigner signer;
        private final String issuer;
        private final String audience;
        private final Clock clock;

        public Signer(ECKey privateKey, String issuer, String audience, Clock clock) {
            this.key = privateKey;
            try {
                this.signer = new ECDSASigner(privateKey);
            } catch (JOSEException e) {
                throw new IllegalArgumentException("not an ES256 signing key", e);
            }
            this.issuer = issuer;
            this.audience = audience;
            this.clock = clock;
        }

        public ECKey publicKey() {
            return key.toPublicJWK();
        }

        /** A user's token for one account: their group names and managed role names there. */
        public String user(String userId, String accountId, String region, List<String> groups, List<String> roles, Duration lifetime) {
            return sign(new JWTClaimsSet.Builder().subject(userId).claim("acct", accountId).claim("region", region).claim("groups", groups)
                    .claim("roles", roles).claim("svc", "console-bff"), lifetime);
        }

        /** A service's token: the internal principal. */
        public String service(String service, Duration lifetime) {
            return sign(new JWTClaimsSet.Builder().subject(service).claim("svc", service), lifetime);
        }

        /** Exactly these claims plus the issuer, audience and times; tests use it for odd tokens. */
        public String sign(JWTClaimsSet.Builder claims, Duration lifetime) {
            Instant now = clock.instant();
            JWTClaimsSet set = claims.issuer(issuer).audience(audience).jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(lifetime))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), set);
            try {
                jwt.sign(signer);
            } catch (JOSEException e) {
                throw new IllegalStateException("cannot sign the token", e);
            }
            return jwt.serialize();
        }
    }
}
