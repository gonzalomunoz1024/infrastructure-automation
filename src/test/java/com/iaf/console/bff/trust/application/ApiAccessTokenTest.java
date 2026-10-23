package com.iaf.console.bff.trust.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.trust.domain.ApiAccessToken;
import com.iaf.console.bff.trust.domain.ApiTokenCheck;
import com.iaf.console.bff.trust.domain.InternalToken;
import com.iaf.console.bff.trust.ports.outbound.SigningKeyPort;
import com.iaf.console.platform.assertion.OnBehalfOfAssertions;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * The console API's access tokens (decision 31): what the trust slice signs, and that it accepts
 * nothing else as one: not another audience or type, not an expired token, not an internal token,
 * a service token or a P-02 assertion, not another key.
 */
class ApiAccessTokenTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final ECKey current = OnBehalfOfAssertions.generateKey("bff-2");
    private final ECKey previous = OnBehalfOfAssertions.generateKey("bff-1");
    private final ECKey stranger = OnBehalfOfAssertions.generateKey("bff-2");
    private final MovingClock clock = new MovingClock(NOW);
    private final TrustProperties properties = new TrustProperties("console-bff", "platform", Duration.ofSeconds(60), Duration.ofSeconds(30), null, null,
            Duration.ofMinutes(5), Duration.ofMinutes(5), null, Map.of());
    private final Trust trust = new Trust(keys(current, previous), properties, clock);
    private final ApiAccessToken token = new ApiAccessToken("gonzalo", "aps_01jabcdefghjkmnpqrstvwxyz0", "console-cli", NOW.minusSeconds(120), NOW,
            NOW.plus(Duration.ofMinutes(15)), "jti-1");

    @Test
    void anAccessTokenCarriesItsTypeKeyAudienceAndClaims() throws Exception {
        SignedJWT jwt = SignedJWT.parse(trust.signApiAccessToken(token));
        assertEquals(new JOSEObjectType("at+jwt"), jwt.getHeader().getType());
        assertEquals(JWSAlgorithm.ES256, jwt.getHeader().getAlgorithm());
        assertEquals("bff-2", jwt.getHeader().getKeyID());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals("console-bff", claims.getIssuer());
        assertEquals(List.of("console-api"), claims.getAudience());
        assertEquals("gonzalo", claims.getSubject());
        assertEquals("aps_01jabcdefghjkmnpqrstvwxyz0", claims.getStringClaim("sid"));
        assertEquals("console-cli", claims.getStringClaim("client_id"));
        assertEquals(NOW.minusSeconds(120).getEpochSecond(), claims.getLongClaim("auth_time"));
        assertEquals(Date.from(NOW), claims.getIssueTime());
        assertEquals(Date.from(NOW.plus(Duration.ofMinutes(15))), claims.getExpirationTime());
        assertEquals("jti-1", claims.getJWTID());
        assertNull(claims.getClaim("svc"), "not an internal token's claims");
        assertNull(claims.getClaim("groups"));
    }

    @Test
    void itsOwnTokenIsAcceptedUntilItExpires() {
        ApiTokenCheck check = trust.verifyApiAccessToken(trust.signApiAccessToken(token));
        assertTrue(check.isValid(), check.refusal());
        assertEquals(token, check.token());

        String signed = trust.signApiAccessToken(token);
        clock.advance(Duration.ofMinutes(15));
        assertEquals("expired", trust.verifyApiAccessToken(signed).refusal());
    }

    @Test
    void aTokenOfThePreviousKeyIsAcceptedAndOneOfAnyOtherKeyIsNot() {
        String byPrevious = new Trust(keys(previous, null), properties, clock).signApiAccessToken(token);
        assertTrue(trust.verifyApiAccessToken(byPrevious).isValid(), "a rotation keeps the previous key's tokens");
        String byStranger = new Trust(keys(stranger, null), properties, clock).signApiAccessToken(token);
        assertEquals("bad signature", trust.verifyApiAccessToken(byStranger).refusal(), "the same kid with another key");
        String unknownKid = new Trust(keys(OnBehalfOfAssertions.generateKey("bff-9"), null), properties, clock).signApiAccessToken(token);
        assertEquals("signed with no key of this service", trust.verifyApiAccessToken(unknownKid).refusal());
        assertFalse(new Trust(keys(stranger, null), properties, clock).verifyApiAccessToken(trust.signApiAccessToken(token)).isValid());
    }

    @Test
    void anInternalTokenAServiceTokenOrAnAssertionIsNeverAnAccessToken() {
        String internal = trust.mint(InternalToken.user("console-identity-service", "user_gonzalo", "123456789012", "global", List.of("owners"),
                List.of("account-admin"), null));
        assertEquals("not an access token (typ)", trust.verifyApiAccessToken(internal).refusal());
        String service = trust.issueServiceToken("console-search-indexer", "console-state-service").token();
        assertFalse(trust.verifyApiAccessToken(service).isValid());
        String assertion = trust.assertionSigner().sign(new OnBehalfOfAssertions.User("user_gonzalo", "Gonzalo Munoz", "gonzalo@example.com",
                List.of("owners")), "123456789012", "global");
        assertFalse(trust.verifyApiAccessToken(assertion).isValid());
        assertThrows(IllegalArgumentException.class, () -> trust.mint(InternalToken.service(Trust.API_AUDIENCE, "console-bff")),
                "no internal token is minted for the API's audience");
        assertThrows(IllegalArgumentException.class, () -> new TrustProperties.Client(List.of("console-state-service", Trust.API_AUDIENCE), null, null, null),
                "no service may be given the API's audience");
    }

    @Test
    void anotherAudienceTypeIssuerOrAlgorithmIsRefused() throws Exception {
        assertEquals("for another audience", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.audience("console"))).refusal());
        assertEquals("for another audience", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.audience(List.of("console-api", "console")))).refusal(),
                "console-api alone");
        assertEquals("not an access token (typ)", trust.verifyApiAccessToken(crafted(current, "JWT", c -> {
        })).refusal());
        assertEquals("not an access token (typ)", trust.verifyApiAccessToken(crafted(current, null, c -> {
        })).refusal());
        assertTrue(trust.verifyApiAccessToken(crafted(current, "application/at+jwt", c -> {
        })).isValid(), "RFC 9068's media type spelling");
        assertEquals("issued by another issuer", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.issuer("someone-else"))).refusal());
        assertEquals("issued in the future", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.issueTime(Date.from(NOW.plusSeconds(120))))).refusal());
        assertEquals("a claim is missing", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.claim("sid", null))).refusal());
        assertEquals("a claim is missing", trust.verifyApiAccessToken(crafted(current, "at+jwt", c -> c.claim("auth_time", null))).refusal());

        SignedJWT hmac = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(new JOSEObjectType("at+jwt")).keyID("bff-2").build(), claims().build());
        hmac.sign(new MACSigner(new byte[32]));
        assertEquals("not signed with ES256", trust.verifyApiAccessToken(hmac.serialize()).refusal());
        assertEquals("not a signed JWT", trust.verifyApiAccessToken(new PlainJWT(claims().build()).serialize()).refusal());
        assertEquals("not a signed JWT", trust.verifyApiAccessToken("not-a-token").refusal());
        assertEquals("no token", trust.verifyApiAccessToken(" ").refusal());
    }

    /** The claims of a good token, to spoil one at a time. */
    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("console-bff").audience("console-api").subject("gonzalo").claim("sid", "aps_01jabcdefghjkmnpqrstvwxyz0")
                .claim("client_id", "console-cli").claim("auth_time", NOW.minusSeconds(60).getEpochSecond()).issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plus(Duration.ofMinutes(15)))).jwtID("jti-2");
    }

    private static String crafted(ECKey key, String type, Consumer<JWTClaimsSet.Builder> spoil) throws JOSEException {
        JWTClaimsSet.Builder claims = claims();
        spoil.accept(claims);
        JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID());
        if (type != null) {
            header.type(new JOSEObjectType(type));
        }
        SignedJWT jwt = new SignedJWT(header.build(), claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    private static SigningKeyPort keys(ECKey key, ECKey before) {
        return new SigningKeyPort() {
            @Override
            public ECKey currentKey() {
                return key;
            }

            @Override
            public ECKey previousKey() {
                return before;
            }
        };
    }

    /** A clock the test moves. */
    private static final class MovingClock extends Clock {

        private Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
