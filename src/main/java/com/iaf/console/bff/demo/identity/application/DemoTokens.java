package com.iaf.console.bff.demo.identity.application;

import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.iaf.console.bff.identity.application.IdentityProperties;
import com.iaf.console.bff.trust.application.Trust;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Verifies the BFF's internal JWTs the way the identity service does: ES256 by kid, iss, aud, exp. */
public class DemoTokens {

    private final Trust trust;
    private final IdentityProperties identity;
    private final Clock clock;

    public DemoTokens(Trust trust, IdentityProperties identity, Clock clock) {
        this.trust = trust;
        this.identity = identity;
        this.clock = clock;
    }

    public Optional<DemoCaller> verify(String authorization) {
        return verify(authorization, identity.serviceName());
    }

    /** The caller of a stand-in that answers as {@code audience}: a token for that service, or for every console service. */
    public Optional<DemoCaller> verify(String authorization, String audience) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring(7).trim());
            JWK key = trust.jwks().getKeyByKeyId(jwt.getHeader().getKeyID());
            if (!(key instanceof ECKey ec) || !jwt.verify(new ECDSAVerifier(ec.toECPublicKey()))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!trust.issuer().equals(claims.getIssuer())) {
                return Optional.empty();
            }
            List<String> audiences = claims.getAudience() == null ? List.of() : claims.getAudience();
            if (!audiences.contains(audience) && !audiences.contains(Trust.CONSOLE_AUDIENCE)) {
                return Optional.empty();
            }
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().plusSeconds(30).isBefore(clock.instant())) {
                return Optional.empty();
            }
            List<String> groups = new ArrayList<>();
            Object raw = claims.getClaim("groups");
            if (raw instanceof List<?> list) {
                list.forEach(g -> groups.add(String.valueOf(g)));
            }
            return Optional.of(new DemoCaller(claims.getSubject(), claims.getStringClaim("acct"), claims.getStringClaim(Trust.SERVICE_CLAIM), groups));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }
}
