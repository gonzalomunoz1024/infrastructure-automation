package com.iaf.platform.identity.adapters.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.iaf.platform.common.domain.Identity;
import com.iaf.platform.config.PlatformProperties;
import com.iaf.platform.config.PlatformProperties.IdentityProps;
import com.iaf.platform.testing.LocalHttp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import reactor.test.StepVerifier;

/** Real signatures against a JWKS served over HTTP: what the verifier accepts, what it refuses, and where it runs. */
class JwtTokenVerifierTest {

    static final String ISSUER = "https://login.example.com/realms/platform";
    static final String AUDIENCE = "platform";

    static RSAKey rsa;
    static RSAKey other;
    static ECKey ec;
    static HttpServer jwks;
    static final AtomicInteger FETCHES = new AtomicInteger();
    final List<JwtTokenVerifier> open = new ArrayList<>();

    @BeforeAll
    static void keys() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("rsa-1").generate();
        other = new RSAKeyGenerator(2048).keyID("rsa-2").generate();
        ec = new ECKeyGenerator(Curve.P_256).keyID("ec-1").generate();
        String published = new JWKSet(List.of(rsa.toPublicJWK(), ec.toPublicJWK())).toString();
        jwks = LocalHttp.start(ex -> {
            FETCHES.incrementAndGet();
            LocalHttp.respond(ex, "/jwks".equals(ex.getRequestURI().getPath()) ? 200 : 404, published);
        });
    }

    @AfterAll
    static void stop() {
        jwks.stop(0);
    }

    @AfterEach
    void close() {
        open.forEach(JwtTokenVerifier::close);
    }

    static IdentityProps identity() {
        IdentityProps p = new IdentityProps();
        p.setMode("oidc");
        p.setOidcIssuer(ISSUER);
        p.setOidcAudience(AUDIENCE);
        p.setOidcJwksUri(LocalHttp.url(jwks) + "/jwks");
        return p;
    }

    JwtTokenVerifier verifier(IdentityProps identity) {
        PlatformProperties props = new PlatformProperties();
        props.setIdentity(identity);
        JwtTokenVerifier verifier = new JwtTokenVerifier(props);
        open.add(verifier);
        return verifier;
    }

    static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder()
                .subject("ana").issuer(ISSUER).audience(AUDIENCE)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plus(Duration.ofHours(1))))
                .claim("name", "Ana").claim("email", "ana@example.com")
                .claim("groups", List.of("team-data", "data-approvers"))
                .claim("roles", List.of("ops", "dba"));
    }

    static String token(JWSAlgorithm alg, String kid, JWSSigner signer, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(alg).keyID(kid).build(), claims);
        jwt.sign(signer);
        return jwt.serialize();
    }

    @Test
    void aGoodTokenResolvesTheNameTheTeamsAndTheRoles() throws Exception {
        JwtTokenVerifier verifier = verifier(identity());
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(rsa), claims().build())))
                .assertNext(who -> {
                    assertThat(who.id()).isEqualTo("ana");
                    assertThat(who.name()).isEqualTo("Ana");
                    assertThat(who.email()).isEqualTo("ana@example.com");
                    assertThat(who.teams()).containsExactly("team-data", "data-approvers");
                    assertThat(who.roles()).containsExactly("ops", "dba");
                    assertThat(who.admin()).isFalse();
                    assertThat(who.kind()).isEqualTo(Identity.Kind.USER);
                })
                .verifyComplete();
        // the subject stands in for a missing name; the admin role makes an admin
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(rsa),
                        claims().claim("name", null).claim("roles", List.of("platform-admin")).build())))
                .assertNext(who -> {
                    assertThat(who.name()).isEqualTo("ana");
                    assertThat(who.admin()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void theClaimNamesAndTheAdminRoleAreConfigurable() throws Exception {
        IdentityProps identity = identity();
        identity.setOidcTeamsClaim("teams");
        identity.setOidcRolesClaim("platform_roles");
        identity.setOidcAdminRole("admins");
        JwtTokenVerifier verifier = verifier(identity);
        JWTClaimsSet claims = claims().claim("teams", List.of("team-platform")).claim("platform_roles", "sre admins").build();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(rsa), claims)))
                .assertNext(who -> {
                    assertThat(who.teams()).containsExactly("team-platform");
                    assertThat(who.roles()).as("a space-separated string claim is read too").containsExactly("sre", "admins");
                    assertThat(who.admin()).isTrue();
                })
                .verifyComplete();
        // with the defaults, the same token carries no teams and no admin role
        StepVerifier.create(verifier(identity()).verify(token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(rsa), claims)))
                .assertNext(who -> {
                    assertThat(who.teams()).containsExactly("team-data", "data-approvers");
                    assertThat(who.admin()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void theWrongIssuerAudienceAnExpiredTokenAndAnUnknownKeyAreRefused() throws Exception {
        JwtTokenVerifier verifier = verifier(identity());
        RSASSASigner signer = new RSASSASigner(rsa);
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer, claims().issuer("https://evil.example.com").build()))).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer, claims().audience("other-app").build()))).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer, claims().audience(List.of()).build()))).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer,
                claims().expirationTime(Date.from(Instant.now().minus(Duration.ofMinutes(5)))).build()))).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer, claims().expirationTime(null).build())))
                .as("no expiry at all").verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", signer, claims().subject(null).build()))).verifyComplete();
        // signed with a key the JWKS does not publish, under its own id or a published one
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-2", new RSASSASigner(other), claims().build()))).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(other), claims().build()))).verifyComplete();
        StepVerifier.create(verifier.verify("not.a.jwt")).verifyComplete();
        StepVerifier.create(verifier.verify("")).verifyComplete();
    }

    @Test
    void theKeyIsSelectedByTheTokensAlgorithmFamilyAndSymmetricSignaturesAreNeverAccepted() throws Exception {
        JwtTokenVerifier verifier = verifier(identity());
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.ES256, "ec-1", new ECDSASigner(ec), claims().build())))
                .assertNext(who -> assertThat(who.id()).isEqualTo("ana")).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.PS256, "rsa-1", new RSASSASigner(rsa), claims().build())))
                .assertNext(who -> assertThat(who.id()).isEqualTo("ana")).verifyComplete();
        StepVerifier.create(verifier.verify(token(JWSAlgorithm.HS256, "rsa-1", new MACSigner(new byte[32]), claims().build())))
                .as("HS256 with any key").verifyComplete();
        assertThat(JwtTokenVerifier.ALGORITHMS).doesNotContain(JWSAlgorithm.HS256, JWSAlgorithm.HS384, JWSAlgorithm.HS512);
    }

    @Test
    void verificationRunsOffTheCallersThreadAndTheKeysAreFetchedOnce() throws Exception {
        Set<String> threads = new CopyOnWriteArraySet<>();
        JWKSource<SecurityContext> source = JwtTokenVerifier.keys(identity());
        JWKSource<SecurityContext> recording = (selector, context) -> {
            threads.add(Thread.currentThread().getName());
            return source.get(selector, context);
        };
        JwtTokenVerifier verifier = new JwtTokenVerifier(recording, identity());
        int before = FETCHES.get();
        String token = token(JWSAlgorithm.RS256, "rsa-1", new RSASSASigner(rsa), claims().build());
        try {
            for (int i = 0; i < 3; i++) {
                StepVerifier.create(verifier.verify(token)).expectNextCount(1).verifyComplete();
            }
        } finally {
            ((Closeable) source).close();
        }
        assertThat(threads).isNotEmpty().allMatch(t -> t.contains("boundedElastic"), "the keys are looked up on boundedElastic, not on the caller's thread");
        assertThat(FETCHES.get() - before).as("the JWKS is cached").isEqualTo(1);
    }

    @Test
    void theIssuerTheAudienceAndTheJwksUriAreRequiredAndCheckedAtStartup() {
        IdentityProps noJwks = identity();
        noJwks.setOidcJwksUri(" ");
        assertThatThrownBy(() -> verifier(noJwks)).isInstanceOf(IllegalStateException.class)
                .hasMessage("platform.identity.oidc-jwks-uri is required under the oidc profile: the issuer's JWKS endpoint, such as https://login.example.com/realms/platform/protocol/openid-connect/certs");
        IdentityProps badJwks = identity();
        badJwks.setOidcJwksUri("not a url");
        assertThatThrownBy(() -> verifier(badJwks)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("platform.identity.oidc-jwks-uri is not a URL: 'not a url'");
        IdentityProps noAudience = identity();
        noAudience.setOidcAudience("");
        assertThatThrownBy(() -> verifier(noAudience)).isInstanceOf(IllegalStateException.class)
                .hasMessage("platform.identity.oidc-audience is required under the oidc profile: the audience (aud) the tokens carry for this platform");
        IdentityProps noIssuer = identity();
        noIssuer.setOidcIssuer(null);
        assertThatThrownBy(() -> verifier(noIssuer)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("platform.identity.oidc-issuer is required under the oidc profile");
    }
}
