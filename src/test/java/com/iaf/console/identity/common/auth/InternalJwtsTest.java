package com.iaf.console.identity.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/** The internal JWT as the BFF signs it, and what the verifier refuses. */
class InternalJwtsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AuthProperties PROPERTIES = new AuthProperties(null, null, null, null, null, null, null, false, null);

    private final ECKey key = generate("k1");
    private final InternalJwts.Signer signer = new InternalJwts.Signer(key, "console-bff", "console-identity-service", CLOCK);
    private final InternalJwts.Verifier verifier = new InternalJwts.Verifier(JwksSource.of(new JWKSet(signer.publicKey())), PROPERTIES, CLOCK);

    private static ECKey generate(String kid) {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID(kid).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void verifiesAUserToken() {
        String token = signer.user("usr_1", "123456789012", "global", List.of("owners", "members"), List.of("account-admin", "operator"), Duration.ofMinutes(5));
        Caller caller = verifier.verify(token).block();
        assertThat(caller).isNotNull();
        assertThat(caller.isUser()).isTrue();
        assertThat(caller.id()).isEqualTo("usr_1");
        assertThat(caller.accountId()).isEqualTo("123456789012");
        assertThat(caller.region()).isEqualTo("global");
        assertThat(caller.groups()).containsExactly("owners", "members");
        assertThat(caller.roles()).containsExactly("account-admin", "operator");
        assertThat(caller.isAccountAdmin()).isTrue();
        assertThat(caller.service()).isEqualTo("console-bff");
        assertThat(caller.actor()).isEqualTo("usr_1");
        assertThat(caller.covers("123456789012")).isTrue();
        assertThat(caller.covers("210987654321")).isFalse();
        assertThat(caller.scope("123456789012").accountId()).isEqualTo("123456789012");
    }

    @Test
    void verifiesAServiceToken() {
        Caller caller = verifier.verify(signer.service("console-state-service", Duration.ofMinutes(5))).block();
        assertThat(caller).isNotNull();
        assertThat(caller.isInternal()).isTrue();
        assertThat(caller.service()).isEqualTo("console-state-service");
        assertThat(caller.actor()).isEqualTo("svc:console-state-service");
        assertThat(caller.covers("210987654321")).isTrue();
        assertThat(caller.roles()).isEmpty();
        assertThat(caller.isAccountAdmin()).isFalse();
    }

    @Test
    void aTokenWithoutRolesCarriesNone() {
        String token = signer.sign(new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("usr_2").claim("acct", "123456789012").claim("region", "global")
                .claim("groups", List.of("owners")).claim("svc", "console-bff"), Duration.ofMinutes(5));
        Caller caller = verifier.verify(token).block();
        assertThat(caller).isNotNull();
        assertThat(caller.groups()).containsExactly("owners");
        assertThat(caller.roles()).as("a group name never stands in for a role").isEmpty();
        assertThat(caller.isAccountAdmin()).isFalse();
        String odd = signer.sign(new com.nimbusds.jwt.JWTClaimsSet.Builder().subject("usr_2").claim("acct", "123456789012").claim("roles", "account-admin")
                .claim("svc", "console-bff"), Duration.ofMinutes(5));
        assertThat(verifier.verify(odd).block().roles()).as("roles that are not a list of strings are none").isEmpty();
    }

    @Test
    void refusesWhatItShould() {
        InternalJwts.Signer otherIssuer = new InternalJwts.Signer(key, "someone-else", "console-identity-service", CLOCK);
        InternalJwts.Signer otherAudience = new InternalJwts.Signer(key, "console-bff", "platform", CLOCK);
        InternalJwts.Signer unknownKey = new InternalJwts.Signer(generate("k2"), "console-bff", "console-identity-service", CLOCK);
        InternalJwts.Signer earlier = new InternalJwts.Signer(key, "console-bff", "console-identity-service", Clock.offset(CLOCK, Duration.ofHours(-2)));

        refuses(otherIssuer.user("usr_1", "123456789012", "global", List.of(), List.of(), Duration.ofMinutes(5)), "another issuer");
        refuses(otherAudience.user("usr_1", "123456789012", "global", List.of(), List.of(), Duration.ofMinutes(5)), "another audience");
        InternalJwts.Signer shared = new InternalJwts.Signer(key, "console-bff", "console", CLOCK);
        refuses(shared.service("console-bff", Duration.ofMinutes(5)), "another audience");
        refuses(unknownKey.user("usr_1", "123456789012", "global", List.of(), List.of(), Duration.ofMinutes(5)), "does not trust");
        refuses(earlier.user("usr_1", "123456789012", "global", List.of(), List.of(), Duration.ofMinutes(5)), "expired");
        refuses(signer.user("usr_1", "123456789012", "global", List.of(), List.of(), Duration.ofHours(2)), "longer than allowed");
        refuses(signer.sign(new JWTClaimsSet.Builder().subject("usr_1").claim("acct", "12"), Duration.ofMinutes(5)), "not an account id");
        refuses(signer.sign(new JWTClaimsSet.Builder().subject("usr_1"), Duration.ofMinutes(5)), "needs svc");
        refuses(signer.sign(new JWTClaimsSet.Builder().claim("acct", "123456789012"), Duration.ofMinutes(5)), "needs a subject");
        refuses("not.a.jwt", "not a compact JWS");
        refuses(hmacToken(), "ES256");
    }

    private void refuses(String token, String reason) {
        assertThatThrownBy(() -> verifier.verify(token).block()).isInstanceOf(UnauthenticatedException.class).hasMessageContaining(reason);
    }

    private static String hmacToken() {
        try {
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject("usr_1").issuer("console-bff").build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
