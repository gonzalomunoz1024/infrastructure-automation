package com.iaf.console.state.auth.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.iaf.console.state.auth.domain.ConsolePrincipal;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** The BFF's internal token: ES256 under a key of its JWKS, our audience, fresh, with the console claims. */
class JwtVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ECKey key = generate("bff-1");
    private final JwtVerifier verifier = new JwtVerifier(new JWKSet(key.toPublicJWK()), "console-bff", List.of("console-state-service", "console"), CLOCK);

    private static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer("console-bff").audience(List.of("console-state-service")).subject("dana")
                .claim("acct", "123456789012").claim("region", "global").claim("groups", List.of("team-compute", "developer")).claim("roles", List.of("operator")).claim("svc", "console-bff")
                .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60)));
    }

    private static String sign(ECKey with, String kid, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).build(), claims);
            jwt.sign(new ECDSASigner(with));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aUserTokenNamesTheUserAndTheirAccount() {
        ConsolePrincipal p = verifier.verify(sign(key, "bff-1", claims().build())).block();
        assertThat(p.subject()).isEqualTo("dana");
        assertThat(p.accountId()).isEqualTo("123456789012");
        assertThat(p.region()).isEqualTo("global");
        assertThat(p.groups()).containsExactly("team-compute", "developer");
        assertThat(p.roles()).containsExactly("operator");
        assertThat(p.mayReveal()).isFalse();
        assertThat(p.service()).isEqualTo("console-bff");
        assertThat(p.internal()).isFalse();
    }

    @Test
    void onlyTheRolesClaimMakesAnAccountAdmin() {
        ConsolePrincipal admin = verifier.verify(sign(key, "bff-1", claims().claim("roles", List.of("operator", "account-admin")).build())).block();
        assertThat(admin.roles()).containsExactly("operator", "account-admin");
        assertThat(admin.mayReveal()).isTrue();
        ConsolePrincipal byGroupOnly = verifier.verify(sign(key, "bff-1", claims().claim("groups", List.of("owners", "account-admin")).claim("roles", null)
                .build())).block();
        assertThat(byGroupOnly.roles()).isEmpty();
        assertThat(byGroupOnly.mayReveal()).isFalse();
    }

    @Test
    void aServiceTokenWithoutAnAccountIsInternal() {
        ConsolePrincipal p = verifier.verify(sign(key, "bff-1", claims().subject("svc:console-bff").claim("acct", null).audience(List.of("console"))
                .build())).block();
        assertThat(p.internal()).isTrue();
        assertThat(p.accountId()).isNull();
    }

    @Test
    void theOnlyKeyServesWhenTheHeaderNamesNone() {
        assertThat(verifier.verify(sign(key, null, claims().build())).block().subject()).isEqualTo("dana");
    }

    @Test
    void whatIsRefused() {
        assertInvalid(sign(key, "bff-1", claims().audience(List.of("console-drift-service")).build()), "audience");
        assertInvalid(sign(key, "bff-1", claims().issuer("someone-else").build()), "issuer");
        assertInvalid(sign(key, "bff-1", claims().expirationTime(Date.from(NOW.minus(Duration.ofSeconds(31)))).build()), "expired");
        assertInvalid(sign(key, "bff-1", claims().notBeforeTime(Date.from(NOW.plus(Duration.ofMinutes(5)))).build()), "not yet valid");
        assertInvalid(sign(key, "bff-1", claims().subject(null).build()), "subject");
        assertInvalid(sign(generate("bff-2"), "bff-2", claims().build()), "no key 'bff-2'");
        assertInvalid(sign(generate("bff-1"), "bff-1", claims().build()), "signature");
        assertInvalid("not.a.jws", "not a JWS");
        assertInvalid(hmac(claims().build()), "ES256");
    }

    @Test
    void clockSkewIsTolerated() {
        String justExpired = sign(key, "bff-1", claims().expirationTime(Date.from(NOW.minusSeconds(10))).build());
        assertThat(verifier.verify(justExpired).block()).isNotNull();
    }

    private void assertInvalid(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token).block()).isInstanceOf(JwtVerifier.InvalidToken.class).hasMessageContaining(reason);
    }

    private static String hmac(JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("bff-1").build(), claims);
            jwt.sign(new MACSigner(new byte[32]));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
