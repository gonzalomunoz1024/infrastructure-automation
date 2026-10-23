package com.iaf.console.cost.auth.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.cost.TestClock;
import com.iaf.console.cost.auth.domain.ConsolePrincipal;
import com.iaf.console.cost.auth.domain.RouteGroup;
import com.iaf.console.cost.auth.domain.ServicePrincipals;
import com.iaf.console.cost.support.Tokens;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;

/**
 * The verifier against tokens shaped as the BFF signs them (hub decision 35): exactly this
 * service as audience, a person only through the BFF, and a token without an account only from a
 * listed console service whose {@code svc} is its {@code sub}, for its route groups; everything
 * else is an invalid token (401), and a verifier without an issuer or audience cannot be made.
 */
class JwtVerifierTest {

    static final String ACCOUNT = "123456789012";
    static final String COST = "console-cost-service";

    private final TestClock clock = new TestClock(Instant.parse("2026-10-23T12:00:00Z"));
    private final Tokens tokens = new Tokens(clock);
    private final JwtVerifier verifier = new JwtVerifier(tokens.jwks(), "console-bff", List.of(COST), ServicePrincipals.defaults(), clock);

    @Test
    void aPersonsTokenForExactlyThisServiceIsThePersonInTheirAccount() {
        ConsolePrincipal p = verifier.verify(tokens.person("dana", ACCOUNT, "account-admin")).block();
        assertThat(p).isNotNull();
        assertThat(p.internal()).isFalse();
        assertThat(p.subject()).isEqualTo("dana");
        assertThat(p.accountId()).isEqualTo(ACCOUNT);
        assertThat(p.region()).isEqualTo("global");
        assertThat(p.roles()).containsExactly("account-admin");
        assertThat(p.service()).isEqualTo("console-bff");
        assertThat(p.routes()).isEmpty();
    }

    @Test
    void aTokenNamingAnyOtherAudienceOrAnotherBesideThisOneIsRefused() {
        List<List<String>> audiences = List.of(List.of("console"), List.of(COST, "console"), List.of("console", COST), List.of("console-state-service"),
                List.of(COST, "console-state-service"), List.of(COST, COST), List.of(" " + COST), List.of(""), List.of());
        for (List<String> aud : audiences) {
            String person = tokens.sign(new JWTClaimsSet.Builder().subject("dana").audience(aud).claim("acct", ACCOUNT).claim("svc", "console-bff"));
            assertThatThrownBy(() -> verifier.verify(person).block()).as("a person's token for %s", aud).isInstanceOf(JwtVerifier.InvalidToken.class)
                    .hasMessageContaining("audience");
            String service = tokens.sign(new JWTClaimsSet.Builder().subject("console-bff").audience(aud).claim("svc", "console-bff"));
            assertThatThrownBy(() -> verifier.verify(service).block()).as("the BFF's own token for %s", aud).isInstanceOf(JwtVerifier.InvalidToken.class)
                    .hasMessageContaining("audience");
        }
        // the single string form of the one audience is the same token as the one-element list
        String single = tokens.sign(new JWTClaimsSet.Builder().subject("dana").audience(COST).claim("acct", ACCOUNT).claim("svc", "console-bff"));
        assertThat(verifier.verify(single).block()).isNotNull();
    }

    @Test
    void theBffAsItselfReachesEveryRouteGroupInTheServicesRegion() {
        ConsolePrincipal bff = verifier.verify(tokens.service("console-bff")).block();
        assertThat(bff).isNotNull();
        assertThat(bff.internal()).isTrue();
        assertThat(bff.subject()).isEqualTo("console-bff");
        assertThat(bff.routes()).containsExactlyInAnyOrder(RouteGroup.PRICING, RouteGroup.ACCOUNTS, RouteGroup.LEDGER);
        // a service token names no region: the filter gives it this service's
        assertThat(bff.region()).isNull();
        assertThat(bff.orRegion("global").region()).isEqualTo("global");
        assertThat(bff.orRegion("global").scope(ACCOUNT, null).region()).isEqualTo("global");
    }

    @Test
    void theDriftServiceAsItselfReachesThePricesAndEstimatesOnly() {
        ConsolePrincipal drift = verifier.verify(tokens.service("console-drift-service")).block();
        assertThat(drift).isNotNull();
        assertThat(drift.routes()).containsExactly(RouteGroup.PRICING);
        drift.require(RouteGroup.PRICING);
        assertThatThrownBy(() -> drift.require(RouteGroup.ACCOUNTS)).hasMessageContaining("console-drift-service may not call the accounts routes");
        assertThatThrownBy(() -> drift.orRegion("global").scope(ACCOUNT, null)).hasMessageContaining("accounts routes");
        assertThatThrownBy(() -> drift.require(RouteGroup.LEDGER)).hasMessageContaining("ledger routes");
        assertThatThrownBy(drift::requireBudgetChanges).hasMessageContaining("accounts routes");
    }

    @Test
    void aTokenWithoutAnAccountFromAnyOtherServiceOrWhoseSvcIsNotItsSubIsRefused() {
        for (String other : List.of("console-search-indexer", "console-state-service", "console-identity-service", "console-e2e", "console")) {
            assertThatThrownBy(() -> verifier.verify(tokens.service(other)).block()).as(other).isInstanceOf(JwtVerifier.InvalidToken.class)
                    .hasMessageContaining("may not call this service as itself");
        }
        // the BFF's or drift's name in svc on someone else's token, or no svc at all
        List<JWTClaimsSet.Builder> claims = List.of(new JWTClaimsSet.Builder().subject("console-search-indexer").claim("svc", "console-bff"),
                new JWTClaimsSet.Builder().subject("dana").claim("svc", "console-drift-service"),
                new JWTClaimsSet.Builder().subject("console-bff").claim("svc", "console-drift-service"),
                new JWTClaimsSet.Builder().subject("console-bff"), new JWTClaimsSet.Builder().subject("console-bff").claim("acct", ""));
        for (JWTClaimsSet.Builder c : claims) {
            String token = tokens.sign(c);
            assertThatThrownBy(() -> verifier.verify(token).block()).as(c.build().toString()).isInstanceOf(JwtVerifier.InvalidToken.class)
                    .hasMessageContaining("svc must be its sub");
        }
    }

    @Test
    void aPersonsTokenComesThroughTheBff() {
        for (String svc : List.of("console-drift-service", "console-search-indexer", "dana")) {
            String token = tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", ACCOUNT).claim("svc", svc));
            assertThatThrownBy(() -> verifier.verify(token).block()).as(svc).isInstanceOf(JwtVerifier.InvalidToken.class)
                    .hasMessageContaining("comes through the BFF");
        }
        String none = tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", ACCOUNT));
        assertThatThrownBy(() -> verifier.verify(none).block()).isInstanceOf(JwtVerifier.InvalidToken.class).hasMessageContaining("comes through the BFF");
    }

    @Test
    void anyOtherFlawIsAnInvalidToken() {
        List<String> bad = List.of(
                tokens.sign(new JWTClaimsSet.Builder().issuer("console-drift-service").subject("dana").claim("acct", ACCOUNT).claim("svc", "console-bff")),
                tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", "1234").claim("svc", "console-bff")),
                tokens.sign(new JWTClaimsSet.Builder().claim("acct", ACCOUNT).claim("svc", "console-bff")),
                tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", ACCOUNT).claim("svc", "console-bff")
                        .expirationTime(Date.from(clock.instant().minusSeconds(31)))),
                tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", ACCOUNT).claim("svc", "console-bff")
                        .notBeforeTime(Date.from(clock.instant().plusSeconds(31)))),
                tokens.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", List.of(ACCOUNT)).claim("svc", "console-bff")),
                // signed with a key the BFF does not publish
                new Tokens(clock).person("dana", ACCOUNT, "account-admin"),
                new PlainJWT(new JWTClaimsSet.Builder().issuer("console-bff").audience(COST).subject("console-bff").claim("svc", "console-bff")
                        .expirationTime(Date.from(clock.instant().plusSeconds(60))).build()).serialize(),
                "not-a-token");
        for (String token : bad) {
            assertThatThrownBy(() -> verifier.verify(token).block()).isInstanceOf(JwtVerifier.InvalidToken.class);
        }
    }

    @Test
    void aVerifierWithoutAnIssuerOrItsOwnAudienceCannotBeMade() {
        ServicePrincipals services = ServicePrincipals.defaults();
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), null, List.of(COST), services, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), " ", List.of(COST), services, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), "console-bff", null, services, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), "console-bff", List.of(), services, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), "console-bff", List.of(" "), services, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtVerifier(tokens.jwks(), "console-bff", List.of(COST, JwtVerifier.SHARED_AUDIENCE), services, clock))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retired");
    }

    @Test
    void anEmptyAllowListLetsNoServiceCallAsItself() {
        JwtVerifier none = new JwtVerifier(tokens.jwks(), "console-bff", List.of(COST), new ServicePrincipals(null, Set.of(), Set.of()), clock);
        assertThatThrownBy(() -> none.verify(tokens.service("console-bff")).block()).isInstanceOf(JwtVerifier.InvalidToken.class);
        assertThat(none.verify(tokens.person("dana", ACCOUNT)).block()).isNotNull();
    }
}
