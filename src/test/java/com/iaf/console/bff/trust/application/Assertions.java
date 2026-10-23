package com.iaf.console.bff.trust.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Client assertions for the service-token tests: a service's own, and a cluster's service-account token. */
public final class Assertions {

    private Assertions() {
    }

    /** What a service signs with its own key: iss and sub its id, aud console-bff, a fresh jti, a minute long. */
    public static String own(ECKey key, String client, Instant now) {
        return signed(key, new JWTClaimsSet.Builder().issuer(client).subject(client).audience(List.of("console-bff")).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(Duration.ofSeconds(60)))).build());
    }

    /** A projected service-account token of the cluster: an hour long, no jti, audience console-bff. */
    public static String serviceAccount(RSAKey key, String issuer, String namespace, String name, Instant now) {
        return signed(key, new JWTClaimsSet.Builder().issuer(issuer).subject("system:serviceaccount:" + namespace + ":" + name)
                .audience(List.of("console-bff")).issueTime(Date.from(now)).notBeforeTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofHours(1)))).build());
    }

    public static String signed(JWK key, JWTClaimsSet claims) {
        try {
            JWSSigner signer;
            JWSAlgorithm alg;
            if (key instanceof ECKey ec) {
                signer = new ECDSASigner(ec);
                alg = JWSAlgorithm.ES256;
            } else if (key instanceof RSAKey rsa) {
                signer = new RSASSASigner(rsa);
                alg = JWSAlgorithm.RS256;
            } else {
                throw new IllegalArgumentException("an EC or RSA key");
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(key.getKeyID()).build(), claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A symmetric one, which the BFF never accepts. */
    public static String hmac(String client, Instant now) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("test-client-1").build(),
                    new JWTClaimsSet.Builder().issuer(client).subject(client).audience(List.of("console-bff")).jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(60))).build());
            byte[] shared = new byte[32];
            new java.security.SecureRandom().nextBytes(shared);
            jwt.sign(new MACSigner(shared));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
