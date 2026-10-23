package com.iaf.console.search.index.adapters.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import com.iaf.console.search.TestClock;
import com.iaf.console.search.support.StubTokenServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Flux;

/** The BFF's service tokens (hub decision 23): what is asked, how a token is kept and renewed, and what a refusal does. */
class ServiceTokensTest {

    private static final String STATE = "console-state-service";
    private static final String IDENTITY = "console-identity-service";

    private final StubTokenServer bff = StubTokenServer.start();
    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final ECKey key = key(Curve.P_256, "indexer-1");

    @AfterEach
    void stop() {
        bff.close();
    }

    private static ECKey key(Curve curve, String kid) {
        try {
            ECKeyGenerator generator = new ECKeyGenerator(curve);
            return (kid == null ? generator : generator.keyID(kid)).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ServiceTokens tokens(ClientAssertion assertion) {
        return new ServiceTokens(WebClient.create(), bff.url(), assertion, ServiceCredentials.SERVICE, clock);
    }

    private ServiceTokens signedTokens() {
        return tokens(ClientAssertion.signed(key.toJSONString(), ServiceCredentials.SERVICE, "console-bff", clock));
    }

    @Test
    void asksWithASignedAssertionAndKeepsTheToken() throws Exception {
        ServiceTokens tokens = signedTokens();
        String token = tokens.token(STATE).block();
        assertThat(token).isEqualTo("tok-1-" + STATE);
        assertThat(tokens.token(STATE).block()).as("kept, not asked again").isEqualTo(token);
        assertThat(bff.forms()).hasSize(1);
        MultiValueMap<String, String> form = bff.forms().get(0);
        assertThat(form.getFirst("grant_type")).isEqualTo("client_credentials");
        assertThat(form.getFirst("client_assertion_type")).isEqualTo("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        assertThat(form.getFirst("client_id")).isEqualTo("console-search-indexer");
        assertThat(form.get("audience")).as("one callee per token").containsExactly(STATE);
        SignedJWT assertion = SignedJWT.parse(form.getFirst("client_assertion"));
        assertThat(assertion.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(assertion.getHeader().getKeyID()).isEqualTo("indexer-1");
        assertThat(assertion.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        JWTClaimsSet claims = assertion.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("console-search-indexer");
        assertThat(claims.getSubject()).isEqualTo("console-search-indexer");
        assertThat(claims.getAudience()).containsExactly("console-bff");
        assertThat(claims.getJWTID()).isNotBlank();
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(clock.instant());
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(clock.instant().plusSeconds(60));
    }

    @Test
    void eachCalleeHasATokenOfItsOwn() {
        ServiceTokens tokens = signedTokens();
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
        assertThat(tokens.token(IDENTITY).block()).isEqualTo("tok-2-" + IDENTITY);
        assertThat(bff.forms()).extracting(f -> f.get("audience")).containsExactly(List.of(STATE), List.of(IDENTITY));
    }

    @Test
    void aTokenIsRenewedAfterFourFifthsOfItsLifeWithAFreshAssertion() throws Exception {
        ServiceTokens tokens = signedTokens();
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
        clock.advance(Duration.ofSeconds(239));
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
        clock.advance(Duration.ofSeconds(2));
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-2-" + STATE);
        Set<String> jtis = bff.forms().stream().map(f -> {
            try {
                return SignedJWT.parse(f.getFirst("client_assertion")).getJWTClaimsSet().getJWTID();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).collect(Collectors.toSet());
        assertThat(jtis).as("the BFF takes each jti once").hasSize(2);
    }

    @Test
    void aRenewalNeverJoinsTheRequestThatJustEnded() {
        ServiceTokens tokens = signedTokens();
        for (int round = 1; round <= 50; round++) {
            assertThat(tokens.token(STATE).block()).isEqualTo("tok-" + round + "-" + STATE);
            clock.advance(Duration.ofSeconds(241));
        }
    }

    @Test
    void callsAtTheSameMomentShareOneRequest() {
        bff.delay(Duration.ofMillis(300));
        ServiceTokens tokens = signedTokens();
        List<String> all = Flux.range(0, 10).flatMap(i -> tokens.token(STATE)).collectList().block();
        assertThat(all).hasSize(10).containsOnly("tok-1-" + STATE);
        assertThat(bff.forms()).hasSize(1);
    }

    @Test
    void whileTheBffCannotAnswerTheCurrentTokenServesUntilItEnds() {
        ServiceTokens tokens = signedTokens();
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
        clock.advance(Duration.ofSeconds(250));
        bff.failNext(503, 503, 503);
        assertThat(tokens.token(STATE).block()).as("past renewal, still good").isEqualTo("tok-1-" + STATE);
        assertThat(bff.forms()).as("the first ask and two retries").hasSize(4);
        clock.advance(Duration.ofSeconds(41));
        bff.failNext(503, 503, 503);
        assertThatThrownBy(() -> tokens.token(STATE).block()).hasMessageContaining("503");
        assertThat(tokens.token(STATE).block()).as("the BFF is back").isEqualTo("tok-2-" + STATE);
    }

    @Test
    void aRefusalIsNotAskedAgainAndKeepsTheAssertionOutOfTheMessage() {
        ServiceTokens tokens = signedTokens();
        bff.failNext(401);
        assertThatThrownBy(() -> tokens.token(STATE).block()).isInstanceOf(ServiceTokens.Refused.class).hasMessageContaining("401 invalid_client")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(bff.forms().get(0).getFirst("client_assertion")));
        assertThat(bff.forms()).hasSize(1);
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
    }

    @Test
    void aTokenTheCalleeRefusedIsForgotten() {
        ServiceTokens tokens = signedTokens();
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-1-" + STATE);
        tokens.refused(STATE, "tok-1-" + STATE);
        assertThat(tokens.token(STATE).block()).isEqualTo("tok-2-" + STATE);
        tokens.refused(STATE, "tok-1-" + STATE);
        assertThat(tokens.token(STATE).block()).as("an old refusal drops nothing").isEqualTo("tok-2-" + STATE);
    }

    @Test
    void theProjectedServiceAccountTokenIsReadForEveryRequest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("token");
        Files.writeString(file, "sa-token-1\n");
        ServiceTokens tokens = tokens(ClientAssertion.projectedToken(file));
        tokens.token(STATE).block();
        Files.writeString(file, "sa-token-2");
        clock.advance(Duration.ofSeconds(241));
        tokens.token(STATE).block();
        assertThat(bff.forms()).extracting(f -> f.getFirst("client_assertion")).containsExactly("sa-token-1", "sa-token-2");
        ServiceTokens missing = tokens(ClientAssertion.projectedToken(dir.resolve("absent")));
        assertThatThrownBy(() -> missing.token(STATE).block()).hasMessageContaining("cannot be read");
    }

    @Test
    void theClientKeyMustBeAPrivateEcKeyWithAKid() throws Exception {
        assertThatThrownBy(() -> ClientAssertion.signed(key.toPublicJWK().toJSONString(), "console-search-indexer", "console-bff", clock))
                .hasMessageContaining("private");
        assertThatThrownBy(() -> ClientAssertion.signed(key(Curve.P_256, null).toJSONString(), "console-search-indexer", "console-bff", clock))
                .hasMessageContaining("kid");
        assertThatThrownBy(() -> ClientAssertion.signed("{\"kty\":\"oct\",\"k\":\"c2VjcmV0\"}", "console-search-indexer", "console-bff", clock))
                .hasMessageContaining("EC JWK");
        String es384 = ClientAssertion.signed(key(Curve.P_384, "indexer-384").toJSONString(), "console-search-indexer", "console-bff", clock).next().block();
        assertThat(SignedJWT.parse(es384).getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES384);
    }
}
