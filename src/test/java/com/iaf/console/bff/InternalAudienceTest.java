package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.iaf.console.bff.demo.identity.application.DemoTokens;
import com.iaf.console.bff.identity.application.IdentityClient;
import com.iaf.console.bff.trust.application.Trust;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** Review finding 2 and hub decision 35: every internal token names exactly its callee, and the stand-ins accept nothing else. */
class InternalAudienceTest extends DemoApp {

    private static final String STATE = "console-state-service";
    private static final String IDENTITY = "console-identity-service";
    private static final String SEARCH = "console-search-indexer";

    @Autowired
    private Trust trust;
    @Autowired
    private IdentityClient identity;
    @Autowired
    private DemoTokens tokens;
    @Autowired
    private SigningKeyPort keys;

    @Test
    void aPersonsTokenNamesOnlyItsCallee() throws Exception {
        String token = trust.mint(InternalToken.user(STATE, "user_dana", COMPUTE, "global", List.of("members"), List.of("operator"), null));
        JWTClaimsSet claims = SignedJWT.parse(token).getJWTClaimsSet();
        assertEquals(List.of(STATE), claims.getAudience(), "never the shared console audience");
        assertEquals(COMPUTE, claims.getStringClaim("acct"));
        assertTrue(tokens.verify("Bearer " + token, STATE).isPresent());
        assertTrue(tokens.verify("Bearer " + token, IDENTITY).isEmpty(), "a state token is no good at identity");
        assertTrue(tokens.verify("Bearer " + token, SEARCH).isEmpty());
    }

    @Test
    void theBffsOwnIdentityCallsAreMintedForIdentityAsItself() throws Exception {
        JWTClaimsSet claims = SignedJWT.parse(identity.serviceToken()).getJWTClaimsSet();
        assertEquals(List.of(IDENTITY), claims.getAudience());
        assertEquals("console-bff", claims.getSubject(), "sub and svc are both the BFF");
        assertEquals("console-bff", claims.getStringClaim("svc"));
        assertFalse(claims.getClaims().containsKey("acct"));
        assertTrue(tokens.verify("Bearer " + identity.serviceToken(), IDENTITY).orElseThrow().internal());
    }

    @Test
    void theStandInsRefuseAnyOtherAudience() throws Exception {
        for (List<String> audience : List.of(List.of("console"), List.of(STATE, "console"), List.of(STATE, IDENTITY), List.<String>of())) {
            String token = signed(claims(audience, "user_dana", "console-bff", COMPUTE));
            assertTrue(tokens.verify("Bearer " + token, STATE).isEmpty(), audience.toString());
            assertTrue(tokens.verify("Bearer " + token, IDENTITY).isEmpty(), audience.toString());
        }
        String console = signed(claims(List.of("console"), "user_gonzalo", "console-bff", COMPUTE));
        assertEquals(401, statusOf("/demo-state/v1/resources?type=vm", console));
        String stateToken = trust.mint(InternalToken.user(STATE, "user_gonzalo", COMPUTE, "global", List.of("owners"), List.of("account-admin"), null));
        assertEquals(200, statusOf("/demo-state/v1/resources?type=vm", stateToken));
        String identityToken = trust.mint(InternalToken.user(IDENTITY, "user_gonzalo", COMPUTE, "global", List.of("owners"), List.of("account-admin"), null));
        assertEquals(401, statusOf("/demo-state/v1/resources?type=vm", identityToken), "a token for identity is no good at state");
    }

    @Test
    void aTokenWithoutAnAccountComesOnlyFromAnAllowedServicePrincipal() throws Exception {
        // the test client is registered for state and identity, not search
        assertTrue(tokens.verify("Bearer " + signed(claims(List.of(STATE), TEST_CLIENT, TEST_CLIENT, null)), STATE).isPresent());
        assertTrue(tokens.verify("Bearer " + signed(claims(List.of(SEARCH), TEST_CLIENT, TEST_CLIENT, null)), SEARCH).isEmpty());
        assertTrue(tokens.verify("Bearer " + signed(claims(List.of(STATE), "user_dana", "console-bff", null)), STATE).isEmpty(),
                "a person's token without an account is refused");
        assertTrue(tokens.verify("Bearer " + signed(claims(List.of(STATE), "console-unknown", "console-unknown", null)), STATE).isEmpty());
    }

    private int statusOf(String path, String token) {
        return console.call(org.springframework.web.reactive.function.client.WebClient.create(BASE).get().uri(path)
                .header("Authorization", "Bearer " + token)).status();
    }

    private static JWTClaimsSet claims(List<String> audience, String subject, String service, String accountId) {
        JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().issuer("console-bff").audience(audience).subject(subject).claim("svc", service)
                .claim("groups", List.of()).claim("roles", List.of()).jwtID(UUID.randomUUID().toString()).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(60)));
        if (accountId != null) {
            b.claim("acct", accountId).claim("region", "global");
        }
        return b.build();
    }

    private String signed(JWTClaimsSet claims) throws JOSEException {
        ECKey key = keys.currentKey();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }
}
