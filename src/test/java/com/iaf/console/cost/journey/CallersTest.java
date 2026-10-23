package com.iaf.console.cost.journey;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.cost.CostServiceApplication;
import com.iaf.console.cost.TestClock;
import com.iaf.console.cost.auth.adapters.web.JwtVerifier;
import com.iaf.console.cost.auth.domain.ServicePrincipals;
import com.iaf.console.cost.boot.CostProperties;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.support.FakeStateService;
import com.iaf.console.cost.support.Tokens;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Who may call what, over HTTP with the BFF's tokens and the dev headers off (hub decision 35 and
 * the cost contract): a token for any audience but exactly this service is a 401, as is a token
 * without an account from a service the contract does not list; the drift service prices and
 * estimates but reads no account's data (403); the BFF as itself reaches every account and the
 * ledger; a person reads their own account and changes its budgets only as an account admin; and
 * without a token only the probes and the build info answer.
 */
@SpringBootTest(classes = CostServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.pricing.source=bundled", "console.ingest.enabled=false", "console.auth.dev-headers=false", "console.outbox.relay.enabled=false" })
@Import(CallersTest.BffKeys.class)
class CallersTest {

    static final String ACCOUNT = "123456789012";
    static final String OTHER = "210987654321";
    static final String VM = "wfrn:wf:compute:global:123456789012:vm/web-01";
    static final String MONTH = "from=2026-10-01&to=2026-11-01";
    static final TestClock CLOCK = new TestClock(Instant.parse("2026-10-10T12:00:00Z"));
    static final Tokens TOKENS = new Tokens(CLOCK);

    /** The BFF's keys are the test's: the verifier is the service's own, over them, with the configured issuer, audience and callers. */
    @TestConfiguration
    static class BffKeys {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        StateServicePort fakeState() {
            return new FakeStateService();
        }

        @Bean
        @Primary
        JwtVerifier testKeysVerifier(CostProperties properties, ServicePrincipals services) {
            return new JwtVerifier(TOKENS.jwks(), properties.auth().issuer(), properties.auth().audiences(), services, CLOCK);
        }
    }

    @Autowired
    WebTestClient web;

    @Test
    void aTokenForAnyAudienceButExactlyThisServiceIs401() {
        for (List<String> aud : List.of(List.of("console"), List.of("console-cost-service", "console"), List.of("console-state-service"),
                List.of("console-cost-service", "console-drift-service"))) {
            String person = TOKENS.sign(new JWTClaimsSet.Builder().subject("dana").audience(aud).claim("acct", ACCOUNT).claim("region", "global")
                    .claim("roles", List.of("account-admin")).claim("svc", "console-bff"));
            as(person).get().uri("/v1/cost?" + MONTH).exchange().expectStatus().isUnauthorized().expectHeader()
                    .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"console\"");
            String bff = TOKENS.sign(new JWTClaimsSet.Builder().subject("console-bff").audience(aud).claim("svc", "console-bff"));
            as(bff).get().uri("/v1/price-lists").exchange().expectStatus().isUnauthorized();
            String drift = TOKENS.sign(new JWTClaimsSet.Builder().subject("console-drift-service").audience(aud).claim("svc", "console-drift-service"));
            estimate(as(drift)).expectStatus().isUnauthorized();
        }
    }

    @Test
    void aTokenWithoutAnAccountFromAServiceTheContractDoesNotListIs401EvenForPrices() {
        for (String service : List.of("console-search-indexer", "console-state-service", "console-e2e")) {
            as(TOKENS.service(service)).get().uri("/v1/price-lists").exchange().expectStatus().isUnauthorized();
            estimate(as(TOKENS.service(service))).expectStatus().isUnauthorized();
        }
        // a listed name in svc is not enough: it must be the token's sub
        String borrowed = TOKENS.sign(new JWTClaimsSet.Builder().subject("console-search-indexer").claim("svc", "console-bff"));
        as(borrowed).get().uri("/v1/ledger/status").exchange().expectStatus().isUnauthorized();
        String unnamed = TOKENS.sign(new JWTClaimsSet.Builder().subject("console-bff"));
        as(unnamed).get().uri("/v1/price-lists").exchange().expectStatus().isUnauthorized();
        // nor a person's token from anyone but the BFF
        String relayed = TOKENS.sign(new JWTClaimsSet.Builder().subject("dana").claim("acct", ACCOUNT).claim("svc", "console-drift-service"));
        as(relayed).get().uri("/v1/cost?" + MONTH).exchange().expectStatus().isUnauthorized();
        // the dev headers are off: they name nobody
        web.get().uri("/v1/price-lists").header("X-Console-Service", "console-bff").exchange().expectStatus().isUnauthorized();
        web.get().uri("/v1/cost?" + MONTH).header("X-Console-Account", ACCOUNT).header("X-Console-Roles", "account-admin").exchange().expectStatus()
                .isUnauthorized();
        web.get().uri("/v1/price-lists").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void theDriftServicePricesAndEstimatesButReadsNoAccountsData() {
        WebTestClient drift = as(TOKENS.service("console-drift-service"));
        estimate(drift).expectStatus().isOk().expectBody().jsonPath("$.monthly").isEqualTo(77.0);
        drift.get().uri("/v1/price-lists").exchange().expectStatus().isOk();

        drift.get().uri("/v1/cost?" + MONTH + "&accountId=" + ACCOUNT).exchange().expectStatus().isForbidden().expectBody()
                .jsonPath("$.error").value(e -> assertThat((String) e).contains("console-drift-service may not call the accounts routes"));
        drift.get().uri("/v1/cost?" + MONTH + "&accountId=" + ACCOUNT + "&accountId=" + OTHER).exchange().expectStatus().isForbidden();
        drift.get().uri("/v1/cost?" + MONTH).exchange().expectStatus().isForbidden();
        drift.get().uri("/v1/wfrn/{w}/cost", VM).exchange().expectStatus().isForbidden();
        drift.get().uri("/v1/chargeback?month=2026-10&accountId=" + ACCOUNT).exchange().expectStatus().isForbidden();
        drift.get().uri("/v1/chargeback.csv?month=2026-10&accountId=" + ACCOUNT).exchange().expectStatus().isForbidden();
        drift.get().uri("/v1/budgets?accountId=" + ACCOUNT).exchange().expectStatus().isForbidden();
        drift.post().uri("/v1/budgets").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Drift's", "amount", 1, "scope", Map.of("accountId", ACCOUNT))).exchange().expectStatus().isForbidden();
        drift.post().uri("/v1/budgets:check").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", ACCOUNT, "monthly", 77)).exchange()
                .expectStatus().isForbidden();
        drift.get().uri("/v1/ledger/status").exchange().expectStatus().isForbidden();
        drift.post().uri("/v1/ledger:sync").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", ACCOUNT)).exchange().expectStatus()
                .isForbidden();
    }

    @Test
    void theBffAsItselfReachesEveryAccountAndTheLedgerInTheServicesRegion() {
        WebTestClient bff = as(TOKENS.service("console-bff"));
        // the organization view: the BFF names the accounts, its token names no region
        bff.get().uri("/v1/cost?" + MONTH + "&groupBy=costCentre&accountId=" + ACCOUNT + "&accountId=" + OTHER).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.accounts.length()").isEqualTo(2);
        bff.post().uri("/v1/budgets:check").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", ACCOUNT, "monthly", 77)).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("allow");
        bff.get().uri("/v1/chargeback?month=2026-10&accountId=" + ACCOUNT).exchange().expectStatus().isOk();
        bff.get().uri("/v1/ledger/status").exchange().expectStatus().isOk();
        estimate(bff).expectStatus().isOk();
        // a call that acts for no account names one
        bff.get().uri("/v1/cost?" + MONTH).exchange().expectStatus().isBadRequest();
    }

    @Test
    void aPersonReadsTheirAccountAndChangesItsBudgetsOnlyAsAnAccountAdmin() {
        WebTestClient admin = as(TOKENS.person("dana", ACCOUNT, "account-admin"));
        WebTestClient viewer = as(TOKENS.person("vic", ACCOUNT, "viewer"));
        WebTestClient none = as(TOKENS.person("eve", ACCOUNT));
        Map<String, Object> budget = Map.of("name", "Compute monthly", "amount", 30, "thresholds", List.of(50, 100));

        viewer.get().uri("/v1/cost?" + MONTH).exchange().expectStatus().isOk();
        estimate(viewer).expectStatus().isOk();
        for (WebTestClient person : List.of(viewer, none)) {
            person.post().uri("/v1/budgets").contentType(MediaType.APPLICATION_JSON).bodyValue(budget).exchange().expectStatus().isForbidden()
                    .expectBody().jsonPath("$.error").isEqualTo("changing a budget takes the account-admin role in the account");
        }
        JsonNode created = admin.post().uri("/v1/budgets").contentType(MediaType.APPLICATION_JSON).bodyValue(budget).exchange().expectStatus()
                .isCreated().expectHeader().valueEquals("ETag", "\"1\"").expectBody(JsonNode.class).returnResult().getResponseBody();
        String id = created.path("budgetId").asText();

        viewer.get().uri("/v1/budgets/{id}", id).exchange().expectStatus().isOk();
        viewer.get().uri("/v1/budgets").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
        Map<String, Object> raised = Map.of("name", "Compute monthly", "amount", 1000);
        viewer.put().uri("/v1/budgets/{id}", id).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).bodyValue(raised).exchange()
                .expectStatus().isForbidden();
        admin.put().uri("/v1/budgets/{id}", id).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).bodyValue(raised).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("ETag", "\"2\"");
        viewer.delete().uri("/v1/budgets/{id}", id).header("If-Match", "\"2\"").exchange().expectStatus().isForbidden();
        none.delete().uri("/v1/budgets/{id}", id).exchange().expectStatus().isForbidden();
        admin.delete().uri("/v1/budgets/{id}", id).header("If-Match", "\"2\"").exchange().expectStatus().isNoContent();

        // another account is a 404 to a person, and so is the ledger's operator side
        admin.get().uri("/v1/cost?" + MONTH + "&accountId=" + OTHER).exchange().expectStatus().isNotFound();
        admin.get().uri("/v1/ledger/status").exchange().expectStatus().isNotFound();
        admin.post().uri("/v1/budgets:check").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", OTHER, "monthly", 1)).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void withoutATokenOnlyTheProbesAndTheBuildInfoAnswer() {
        for (String open : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/info")) {
            web.get().uri(open).exchange().expectStatus().isOk();
        }
        web.get().uri("/actuator/health").exchange().expectBody().jsonPath("$.status").isEqualTo("UP").jsonPath("$.components").doesNotExist();
        for (String closed : List.of("/actuator", "/actuator/prometheus", "/actuator/metrics", "/actuator/env", "/actuator/beans",
                "/actuator/configprops", "/actuator/loggers", "/actuator/heapdump", "/actuator/health/db", "/actuator/healthz", "/actuator/info/x",
                "/.well-known/jwks.json")) {
            web.get().uri(closed).exchange().expectStatus().isUnauthorized();
        }
        web.post().uri("/actuator/health").exchange().expectStatus().isUnauthorized();
        web.post().uri("/actuator/loggers/com.iaf").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("configuredLevel", "TRACE")).exchange()
                .expectStatus().isUnauthorized();
        // with a token they are no more than the exposure: health, info and prometheus
        WebTestClient bff = as(TOKENS.service("console-bff"));
        for (String off : List.of("/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/loggers", "/actuator/heapdump")) {
            bff.get().uri(off).exchange().expectStatus().isNotFound();
        }
    }

    private WebTestClient as(String token) {
        return web.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token).build();
    }

    private static WebTestClient.ResponseSpec estimate(WebTestClient client) {
        return client.post().uri("/v1/cost:estimate").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "vm", "attributes", Map.of("cpu", 4), "count", 1)).exchange();
    }
}
