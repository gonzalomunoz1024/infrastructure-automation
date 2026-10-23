package com.iaf.console.bff.demo.identity.application;

import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.application.TrustProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Verifies the BFF's internal JWTs the way the console's services do (hub decision 35): ES256 by
 * kid, iss, exactly the one audience of the stand-in asked ({@code aud} naming anything else, or
 * more than one service, is refused), exp; a token without {@code acct} only from a service
 * principal allowed to call that service as itself ({@code svc} equal to {@code sub}: the BFF, or
 * a client registered for the audience).
 */
public class DemoTokens {

    private final Trust trust;
    private final TrustProperties trustProperties;
    private final Clock clock;

    public DemoTokens(Trust trust, TrustProperties trustProperties, Clock clock) {
        this.trust = trust;
        this.trustProperties = trustProperties;
        this.clock = clock;
    }

    /** The caller of a stand-in whose service id is {@code audience}; empty when the token is not one it accepts. */
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
            if (!List.of(audience).equals(claims.getAudience())) {
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
            String accountId = claims.getStringClaim("acct");
            String service = claims.getStringClaim(Trust.SERVICE_CLAIM);
            if (accountId == null && !servicePrincipal(service, claims.getSubject(), audience)) {
                return Optional.empty();
            }
            return Optional.of(new DemoCaller(claims.getSubject(), accountId, service, groups));
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    /** The BFF itself, or a client registered for the audience, naming itself as both svc and sub. */
    private boolean servicePrincipal(String service, String subject, String audience) {
        if (service == null || !service.equals(subject)) {
            return false;
        }
        if (service.equals(trust.issuer())) {
            return true;
        }
        TrustProperties.Client client = trustProperties.clients().get(service);
        return client != null && client.audiences().contains(audience);
    }
}
