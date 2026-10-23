package com.iaf.console.bff.trust.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.trust.domain.IssuedServiceToken;
import com.iaf.console.bff.trust.domain.ServiceTokenException;
import com.iaf.console.bff.trust.domain.ServiceTokenRequest;
import com.iaf.console.bff.trust.ports.outbound.ClientKeysPort;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/** Who gets a service token and what it says (hub decision 23). */
class ServiceTokensTest {

    private static final String CLUSTER = "https://kubernetes.test";
    private static final String STATE = "console-state-service";
    private static final String IDENTITY = "console-identity-service";

    private final Instant now = Instant.parse("2026-10-23T14:00:00Z");
    private final ECKey signing = OnBehalfOfAssertions.generateKey("console-bff-test");
    private final ECKey indexerKey = generateEc("indexer-1");
    private final RSAKey clusterKey = generateRsa("cluster-1");
    private final Map<String, TrustProperties.Client> clients = Map.of(
            "console-search-indexer", new TrustProperties.Client(List.of(STATE, IDENTITY), "console:console-search-indexer", "{\"kty\":\"EC\"}", null),
            "console-drift-service", new TrustProperties.Client(List.of(STATE), "console:console-drift-service", null, null));

    private ServiceTokens tokens(boolean cluster, Clock clock) {
        TrustProperties properties = new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null, null,
                Duration.ofMinutes(5), Duration.ofMinutes(5), new TrustProperties.Kubernetes(cluster ? CLUSTER : null, null, null, null, null), clients);
        SigningKeyPort keys = new SigningKeyPort() {
            @Override
            public ECKey currentKey() {
                return signing;
            }

            @Override
            public ECKey previousKey() {
                return null;
            }
        };
        ClientKeysPort clientKeys = new ClientKeysPort() {
            @Override
            public Mono<JWK> clientKey(String clientId, String kid) {
                return "console-search-indexer".equals(clientId) && indexerKey.getKeyID().equals(kid) ? Mono.just(indexerKey.toPublicJWK()) : Mono.empty();
            }

            @Override
            public Mono<JWK> clusterKey(String kid) {
                return cluster && clusterKey.getKeyID().equals(kid) ? Mono.just(clusterKey.toPublicJWK()) : Mono.empty();
            }
        };
        return new ServiceTokens(new Trust(keys, properties, clock), properties, clientKeys, clock);
    }

    private ServiceTokens tokens() {
        return tokens(true, Clock.fixed(now, ZoneOffset.UTC));
    }

    /** A request for the state service, the audience every test client is registered for. */
    private static ServiceTokenRequest request(String assertion) {
        return requestFor(assertion, STATE);
    }

    private static ServiceTokenRequest requestFor(String assertion, String... audiences) {
        return new ServiceTokenRequest(ServiceTokenRequest.CLIENT_CREDENTIALS, ServiceTokenRequest.JWT_BEARER, assertion, null, List.of(audiences));
    }

    private ServiceTokenException refused(ServiceTokens tokens, ServiceTokenRequest request) {
        return assertThrows(ServiceTokenException.class, () -> tokens.issue(request).block());
    }

    @Test
    void aServiceSignedAssertionGetsAFiveMinuteTokenForTheOneServiceAskedFor() throws Exception {
        IssuedServiceToken issued = tokens().issue(requestFor(Assertions.own(indexerKey, "console-search-indexer", now), IDENTITY)).block();
        assertEquals(300, issued.expiresIn());
        assertEquals(IDENTITY, issued.audience());
        SignedJWT jwt = SignedJWT.parse(issued.token());
        assertTrue(jwt.verify(new ECDSAVerifier(signing.toECPublicKey())), "signed with the BFF's key");
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("console-bff", claims.getIssuer());
        assertEquals(List.of(IDENTITY), claims.getAudience(), "exactly the service asked for, never the shared console audience");
        assertEquals("console-search-indexer", claims.getSubject());
        assertEquals("console-search-indexer", claims.getStringClaim("svc"));
        assertNull(claims.getClaim("acct"), "no account: the console-internal principal");
        assertEquals(Duration.ofMinutes(5), Duration.between(claims.getIssueTime().toInstant(), claims.getExpirationTime().toInstant()));
        assertTrue(claims.getJWTID() != null);
    }

    @Test
    void aTokenIsForExactlyOneRegisteredService() {
        IssuedServiceToken one = tokens().issue(requestFor(Assertions.own(indexerKey, "console-search-indexer", now), STATE)).block();
        assertEquals(STATE, one.audience());
        for (ServiceTokenRequest asked : List.of(
                requestFor(Assertions.own(indexerKey, "console-search-indexer", now)),
                requestFor(Assertions.own(indexerKey, "console-search-indexer", now), STATE, IDENTITY),
                requestFor(Assertions.own(indexerKey, "console-search-indexer", now), IDENTITY + " " + STATE),
                requestFor(Assertions.own(indexerKey, "console-search-indexer", now), "console-bff"),
                requestFor(Assertions.own(indexerKey, "console-search-indexer", now), "console"))) {
            ServiceTokenException e = refused(tokens(), asked);
            assertEquals("invalid_target", e.error(), asked.audiences().toString());
            assertEquals(400, e.status());
        }
    }

    @Test
    void anAssertionForAnotherAudienceBesideTheBffIsRefused() {
        String both = Assertions.signed(indexerKey, new JWTClaimsSet.Builder().issuer("console-search-indexer").subject("console-search-indexer")
                .audience(List.of("console-bff", STATE)).jwtID(UUID.randomUUID().toString()).issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(60))).build());
        ServiceTokenException e = refused(tokens(), request(both));
        assertEquals("invalid_client", e.error());
        assertTrue(e.getMessage().contains("exactly console-bff"), e.getMessage());
    }

    @Test
    void anAssertionIsUsedOnce() {
        ServiceTokens tokens = tokens();
        String assertion = Assertions.own(indexerKey, "console-search-indexer", now);
        tokens.issue(request(assertion)).block();
        ServiceTokenException e = refused(tokens, request(assertion));
        assertEquals("invalid_client", e.error());
        assertEquals(401, e.status());
        assertTrue(e.getMessage().contains("used before"), e.getMessage());
    }

    @Test
    void anAssertionMustBeShortCurrentForTheBffAndItsClientsOwn() {
        ServiceTokens tokens = tokens();
        String expired = Assertions.own(indexerKey, "console-search-indexer", now.minus(Duration.ofMinutes(5)));
        assertTrue(refused(tokens, request(expired)).getMessage().contains("expired"));
        String tooLong = Assertions.signed(indexerKey, claims("console-search-indexer", "console-search-indexer", "console-bff", now, Duration.ofMinutes(30)));
        assertTrue(refused(tokens, request(tooLong)).getMessage().contains("longer"));
        String elsewhere = Assertions.signed(indexerKey, claims("console-search-indexer", "console-search-indexer", "console-state-service", now, Duration.ofSeconds(60)));
        assertTrue(refused(tokens, request(elsewhere)).getMessage().contains("exactly console-bff"));
        String someoneElse = Assertions.signed(indexerKey, claims("console-search-indexer", "console-drift-service", "console-bff", now, Duration.ofSeconds(60)));
        assertEquals("invalid_client", refused(tokens, request(someoneElse)).error());
        String stranger = Assertions.own(indexerKey, "console-unknown", now);
        assertTrue(refused(tokens, request(stranger)).getMessage().contains("no client"));
        String keyless = Assertions.own(indexerKey, "console-drift-service", now);
        assertTrue(refused(tokens, request(keyless)).getMessage().contains("no registered keys"));
        String forged = Assertions.own(generateEc("indexer-1"), "console-search-indexer", now);
        assertTrue(refused(tokens, request(forged)).getMessage().contains("does not verify"));
        assertTrue(refused(tokens, request(Assertions.hmac("console-search-indexer", now))).getMessage().contains("algorithm"));
        assertTrue(refused(tokens, request("not-a-jwt")).getMessage().contains("not a signed JWT"));
    }

    @Test
    void theRequestNamesTheGrantAndTheAssertion() {
        ServiceTokens tokens = tokens();
        String assertion = Assertions.own(indexerKey, "console-search-indexer", now);
        assertEquals("unsupported_grant_type",
                refused(tokens, new ServiceTokenRequest("password", ServiceTokenRequest.JWT_BEARER, assertion, null, List.of())).error());
        assertEquals("invalid_request", refused(tokens, new ServiceTokenRequest(null, ServiceTokenRequest.JWT_BEARER, assertion, null, List.of())).error());
        assertEquals("invalid_request",
                refused(tokens, new ServiceTokenRequest(ServiceTokenRequest.CLIENT_CREDENTIALS, "urn:other", assertion, null, List.of())).error());
        assertEquals("invalid_request",
                refused(tokens, new ServiceTokenRequest(ServiceTokenRequest.CLIENT_CREDENTIALS, ServiceTokenRequest.JWT_BEARER, " ", null, List.of())).error());
        assertEquals("invalid_client", refused(tokens, new ServiceTokenRequest(ServiceTokenRequest.CLIENT_CREDENTIALS, ServiceTokenRequest.JWT_BEARER,
                assertion, "console-drift-service", List.of())).error());
    }

    @Test
    void aClusterServiceAccountTokenProvesItsRegisteredClientAgainAndAgain() throws Exception {
        ServiceTokens tokens = tokens();
        String token = Assertions.serviceAccount(clusterKey, CLUSTER, "console", "console-drift-service", now);
        IssuedServiceToken first = tokens.issue(request(token)).block();
        IssuedServiceToken again = tokens.issue(request(token)).block();
        assertEquals("console-drift-service", first.clientId());
        assertEquals(STATE, again.audience());
        assertEquals("console-drift-service", SignedJWT.parse(again.token()).getJWTClaimsSet().getStringClaim("svc"));

        String other = Assertions.serviceAccount(clusterKey, CLUSTER, "default", "console-drift-service", now);
        assertTrue(refused(tokens, request(other)).getMessage().contains("not a registered client"));
        String node = Assertions.signed(clusterKey, new JWTClaimsSet.Builder().issuer(CLUSTER).subject("system:node:worker-1")
                .audience(List.of("console-bff")).expirationTime(Date.from(now.plusSeconds(600))).build());
        assertTrue(refused(tokens, request(node)).getMessage().contains("service account"));
        String forever = Assertions.signed(clusterKey, new JWTClaimsSet.Builder().issuer(CLUSTER).subject("system:serviceaccount:console:console-drift-service")
                .audience(List.of("console-bff")).build());
        assertTrue(refused(tokens, request(forever)).getMessage().contains("needs exp"));
        String apiServer = Assertions.signed(clusterKey, new JWTClaimsSet.Builder().issuer(CLUSTER).subject("system:serviceaccount:console:console-drift-service")
                .audience(List.of("https://kubernetes.default.svc")).expirationTime(Date.from(now.plusSeconds(600))).build());
        assertTrue(refused(tokens, request(apiServer)).getMessage().contains("exactly console-bff"));
    }

    @Test
    void anUntrustedClusterProvesNobody() {
        String token = Assertions.serviceAccount(clusterKey, CLUSTER, "console", "console-drift-service", now);
        ServiceTokenException e = refused(tokens(false, Clock.fixed(now, ZoneOffset.UTC)), request(token));
        assertEquals("invalid_client", e.error());
    }

    private static JWTClaimsSet claims(String iss, String sub, String aud, Instant iat, Duration life) {
        return new JWTClaimsSet.Builder().issuer(iss).subject(sub).audience(List.of(aud)).jwtID(UUID.randomUUID().toString())
                .issueTime(Date.from(iat)).expirationTime(Date.from(iat.plus(life))).build();
    }

    private static ECKey generateEc(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generateRsa(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
