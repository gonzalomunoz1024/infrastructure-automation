package com.iaf.console.bff.trust.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.bff.trust.domain.IssuedServiceToken;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The BFF as a signer: internal JWTs for the identity and state services ({@code iss} the BFF,
 * {@code aud} the target service and {@code console}, {@code svc console-bff}), service tokens for
 * the console's other services (hub decision 23), P-02 assertions for the platform, and the JWKS
 * every verifier reads. One ES256 key signs both; the JWKS carries the
 * previous key too while a rotation lasts.
 */
public class Trust {

    /** The claim naming the calling console service. */
    public static final String SERVICE_CLAIM = "svc";
    /** The audience every console service accepts beside its own name. */
    public static final String CONSOLE_AUDIENCE = "console";

    private final SigningKeyPort keys;
    private final TrustProperties properties;
    private final Clock clock;

    public Trust(SigningKeyPort keys, TrustProperties properties, Clock clock) {
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
    }

    public String issuer() {
        return properties.issuer();
    }

    /** The public keys, for {@code /.well-known/jwks.json}. */
    public JWKSet jwks() {
        List<JWK> published = new ArrayList<>();
        published.add(keys.currentKey().toPublicJWK());
        ECKey previous = keys.previousKey();
        if (previous != null) {
            published.add(previous.toPublicJWK());
        }
        return new JWKSet(published);
    }

    /** A signer of P-02 assertions for the platform, with the current key. */
    public OnBehalfOfAssertions.Signer assertionSigner() {
        return new OnBehalfOfAssertions.Signer(keys.currentKey(), properties.issuer(), properties.platformAudience(), clock, properties.assertionLifetime());
    }

    /**
     * A service token (hub decision 23) for a console service calling others on its own: {@code aud}
     * only the services asked for, {@code sub} and {@code svc} the service, no account.
     */
    public IssuedServiceToken issueServiceToken(String serviceId, List<String> audiences) {
        Instant now = clock.instant();
        Duration lifetime = properties.serviceTokenLifetime();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(List.copyOf(audiences))
                .subject(serviceId)
                .claim(SERVICE_CLAIM, serviceId)
                .claim("groups", List.of())
                .claim("roles", List.of())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(lifetime)))
                .build();
        return new IssuedServiceToken(sign(claims), lifetime.toSeconds(), audiences, serviceId);
    }

    /** A compact JWS for one call to another console service; it ends with the token's lifetime or when its roles stop being current. */
    public String mint(InternalToken token) {
        Instant now = clock.instant();
        Instant expires = now.plus(properties.tokenLifetime());
        if (token.notAfter() != null && token.notAfter().isBefore(expires)) {
            expires = token.notAfter();
        }
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(properties.issuer())
                .audience(List.of(token.audience(), CONSOLE_AUDIENCE))
                .subject(token.subject())
                .claim(SERVICE_CLAIM, properties.issuer())
                .claim("groups", token.groups())
                .claim("roles", token.roles())
                .jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(expires));
        if (token.accountId() != null) {
            claims.claim("acct", token.accountId());
        }
        if (token.region() != null) {
            claims.claim("region", token.region());
        }
        return sign(claims.build());
    }

    private String sign(JWTClaimsSet claims) {
        ECKey key = keys.currentKey();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new ECDSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign an internal token", e);
        }
        return jwt.serialize();
    }
}
