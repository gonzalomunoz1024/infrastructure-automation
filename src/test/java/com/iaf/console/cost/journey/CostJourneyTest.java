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
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.cost.CostServiceApplication;
import com.iaf.console.cost.TestClock;
import com.iaf.console.cost.budgets.application.BudgetEvaluator;
import com.iaf.console.cost.chargeback.application.MonthCloser;
import com.iaf.console.cost.ledger.application.DayCloser;
import com.iaf.console.cost.ledger.application.LedgerService;
import com.iaf.console.cost.ledger.domain.ResourceSnapshot;
import com.iaf.console.cost.ledger.ports.outbound.StateServicePort;
import com.iaf.console.cost.support.FakeStateService;
import com.iaf.console.kit.outbox.OutboxEntry;
import com.iaf.console.kit.outbox.OutboxPort;

/**
 * The service end to end over HTTP, in memory, with the state service a map and the clock in the
 * test's hand: two resources priced from the bundled lists, the Cost Explorer, one resource's
 * cost, a budget that goes over and alerts, the create check it then denies, If-Match, the
 * month's chargeback before and after it is published, estimates, and the account boundary.
 */
@SpringBootTest(classes = CostServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "console.pricing.source=bundled", "console.ingest.enabled=false", "console.auth.dev-headers=true", "console.ledger.backfill-days=30",
        "console.outbox.relay.enabled=false" })
@Import(CostJourneyTest.Fakes.class)
class CostJourneyTest {

    static final String ACCOUNT = "123456789012";
    static final String OTHER = "210987654321";
    static final String VM = "wfrn:wf:compute:global:123456789012:vm/web-01";
    static final String DB = "wfrn:wf:database:global:123456789012:database/db-01";
    static final TestClock CLOCK = new TestClock(Instant.parse("2026-10-10T12:00:00Z"));
    static final FakeStateService STATE = new FakeStateService();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        StateServicePort fakeState() {
            return STATE;
        }
    }

    @Autowired
    WebTestClient web;
    @Autowired
    LedgerService ledger;
    @Autowired
    DayCloser closer;
    @Autowired
    BudgetEvaluator evaluator;
    @Autowired
    MonthCloser months;
    @Autowired
    OutboxPort outbox;

    @Test
    void costsBudgetsAndChargebackFromTwoResources() {
        ResourceSnapshot vm = resource(VM, "vm", "compute", Map.of("cpu", 4), Map.of("team", "web"), "2026-10-01T00:00:00Z");
        ResourceSnapshot db = resource(DB, "database", "database", Map.of("tier", "M10"), Map.of(), "2026-10-05T00:00:00Z");
        STATE.put(vm);
        STATE.put(db);
        assertThat(ledger.apply(LedgerService.self(ACCOUNT), vm).block()).isTrue();
        assertThat(ledger.apply(LedgerService.self(ACCOUNT), db).block()).isTrue();
        assertThat(ledger.apply(LedgerService.self(ACCOUNT), vm).block()).as("the same version again").isFalse();
        assertThat(closer.tick().block()).isPositive();

        // 9.5 days of $77 and 5.5 days of $57 in a 31-day month
        JsonNode report = person().get().uri("/v1/cost?from=2026-10-01&to=2026-11-01&groupBy=service").exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(report.path("total").asDouble()).isEqualTo(33.71);
        assertThat(report.path("groups")).hasSize(2);
        assertThat(report.path("groups").get(0).path("key").asText()).isEqualTo("compute");
        assertThat(report.path("runRate").path("monthly").asDouble()).isEqualTo(134.0);
        assertThat(report.path("forecast").path("monthEnd").asDouble()).isGreaterThan(33.71);
        assertThat(report.path("closedThrough").asText()).isEqualTo("2026-10-09");
        assertThat(report.path("series").get(0).path("provisional").asBoolean()).isFalse();
        assertThat(report.path("series").get(9).path("provisional").asBoolean()).as("today, still open").isTrue();

        JsonNode byTag = person().get().uri("/v1/cost?from=2026-10-01&to=2026-11-01&granularity=month&groupBy=tag:team&tag.team=web").exchange()
                .expectStatus().isOk().expectBody(JsonNode.class).returnResult().getResponseBody();
        assertThat(byTag.path("total").asDouble()).isEqualTo(23.60);

        JsonNode one = person().get().uri("/v1/wfrn/{w}/cost", VM).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult()
                .getResponseBody();
        assertThat(one.path("monthly").asDouble()).isEqualTo(77.0);
        assertThat(one.path("monthToDate").asDouble()).isEqualTo(23.60);
        assertThat(one.path("rule").path("by").asText()).isEqualTo("cpu");

        // another account's resource and report are a 404, never a 403
        person().get().uri("/v1/wfrn/{w}/cost", VM.replace(ACCOUNT, OTHER)).exchange().expectStatus().isNotFound();
        person().get().uri("/v1/cost?from=2026-10-01&to=2026-11-01&accountId=" + OTHER).exchange().expectStatus().isNotFound();
        person().get().uri("/v1/cost?from=2026-10-01").exchange().expectStatus().isBadRequest();

        // the organization view: the BFF names the accounts
        service().get().uri("/v1/cost?from=2026-10-01&to=2026-11-01&groupBy=costCentre&accountId=" + ACCOUNT + "&accountId=" + OTHER).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.groups[0].key").isEqualTo("CC-1042").jsonPath("$.accounts.length()").isEqualTo(2);

        // a budget of $30 with an alert at half: already over, and it blocks creates
        JsonNode budget = person().post().uri("/v1/budgets").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Compute monthly", "amount", 30, "thresholds", List.of(50, 100), "blockCreates", true)).exchange()
                .expectStatus().isCreated().expectHeader().valueEquals("ETag", "\"1\"").expectBody(JsonNode.class).returnResult().getResponseBody();
        String id = budget.path("budgetId").asText();
        assertThat(id).matches("^bud_[0-9a-hjkmnp-tv-z]{26}$");
        assertThat(budget.path("status").path("state").asText()).isEqualTo("over");
        assertThat(budget.path("status").path("spent").asDouble()).isEqualTo(33.71);
        person().post().uri("/v1/budgets").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Compute monthly", "amount", 30, "thresholds", List.of(50, 100), "blockCreates", true)).exchange()
                .expectStatus().isCreated().expectBody().jsonPath("$.budgetId").isEqualTo(id);
        person().post().uri("/v1/budgets").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Something else", "amount", 30)).exchange().expectStatus().isEqualTo(409);

        assertThat(evaluator.tick().block()).isEqualTo(2L);
        assertThat(evaluator.tick().block()).as("each threshold alerts once a month").isZero();
        List<OutboxEntry> events = outbox.after(0, 100).collectList().block();
        assertThat(events).extracting(e -> e.event().type()).containsSubsequence("BudgetChanged", "BudgetThresholdCrossed", "BudgetThresholdCrossed");
        assertThat(events).extracting(OutboxEntry::key).contains("BudgetThresholdCrossed:" + ACCOUNT + ":" + id + ":2026-10-100");
        person().get().uri("/v1/budgets/{id}", id).exchange().expectStatus().isOk().expectBody().jsonPath("$.status.alertsSent[1]").isEqualTo(100);

        service().post().uri("/v1/budgets:check").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", ACCOUNT, "type", "vm", "tags", Map.of("team", "web"), "monthly", 77)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.decision").isEqualTo("deny").jsonPath("$.rules[0].id").isEqualTo("budget.exceeded")
                .jsonPath("$.rules[0].budgetId").isEqualTo(id);

        // If-Match: required, then the version moves
        Map<String, Object> raised = Map.of("name", "Compute monthly", "amount", 1000, "blockCreates", true);
        person().put().uri("/v1/budgets/{id}", id).contentType(MediaType.APPLICATION_JSON).bodyValue(raised).exchange().expectStatus().isEqualTo(428);
        person().put().uri("/v1/budgets/{id}", id).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).bodyValue(raised).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("ETag", "\"2\"").expectBody().jsonPath("$.status.state").isEqualTo("ok");
        person().put().uri("/v1/budgets/{id}", id).header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON).bodyValue(raised).exchange()
                .expectStatus().isEqualTo(409);
        service().post().uri("/v1/budgets:check").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", ACCOUNT, "monthly", 77))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.decision").isEqualTo("allow");
        person().get().uri("/v1/budgets").exchange().expectStatus().isOk().expectBody().jsonPath("$.items.length()").isEqualTo(1);
        person().get().uri("/v1/budgets/{id}/periods", id).exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].period")
                .isEqualTo("2026-10");
        other().get().uri("/v1/budgets/{id}", id).exchange().expectStatus().isNotFound();

        // this month's chargeback is provisional
        person().get().uri("/v1/chargeback?month=2026-10&tag=team").exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.final").isEqualTo(false).jsonPath("$.total").isEqualTo(33.71).jsonPath("$.rows.length()").isEqualTo(2);

        // November: October closes, is published once, and is final from then on
        CLOCK.set(Instant.parse("2026-11-01T01:00:00Z"));
        closer.tick().block();
        assertThat(months.tick().block()).isEqualTo(1L);
        assertThat(months.tick().block()).isZero();
        JsonNode october = person().get().uri("/v1/chargeback?month=2026-10").exchange().expectStatus().isOk().expectBody(JsonNode.class)
                .returnResult().getResponseBody();
        assertThat(october.path("final").asBoolean()).isTrue();
        // all of October at $77 and 27 days at $57
        assertThat(october.path("total").asDouble()).isEqualTo(126.65);
        String sha = october.path("sha256").asText();
        String csv = person().get().uri("/v1/chargeback.csv?month=2026-10").exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Content-Sha256", sha).expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).startsWith("month,cost_centre,account_id").contains("2026-10,CC-1042,123456789012,Payments production,compute,,,77.00,USD,true");
        assertThat(outbox.after(0, 100).collectList().block()).extracting(OutboxEntry::key).contains("ChargebackPublished:" + ACCOUNT + ":2026-10:1");

        // prices and estimates belong to no account
        person().post().uri("/v1/cost:estimate").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("type", "vm", "attributes", Map.of("cpu", 4), "count", 3)).exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.monthly").isEqualTo(231.0).jsonPath("$.source").isEqualTo("list-prices");
        person().get().uri("/v1/price-lists").exchange().expectStatus().isOk().expectBody().jsonPath("$.items[0].name").isEqualTo("list-prices")
                .jsonPath("$.bindings.length()").isEqualTo(2);

        // the ledger's operator calls are the console's own
        person().get().uri("/v1/ledger/status").exchange().expectStatus().isNotFound();
        service().get().uri("/v1/ledger/status").exchange().expectStatus().isOk().expectBody().jsonPath("$.resources").isEqualTo(2)
                .jsonPath("$.closedThrough").isEqualTo("2026-10-31");
        String run = service().post().uri("/v1/ledger:sync").contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("accountId", ACCOUNT))
                .exchange().expectStatus().isAccepted().expectBody(JsonNode.class).returnResult().getResponseBody().path("runId").asText();
        service().get().uri("/v1/ledger/syncs/{run}", run).exchange().expectStatus().isOk();

        person().delete().uri("/v1/budgets/{id}", id).header("If-Match", "\"2\"").exchange().expectStatus().isNoContent();
        person().get().uri("/v1/budgets/{id}", id).exchange().expectStatus().isNotFound();
    }

    private WebTestClient person() {
        return web.mutate().defaultHeader("X-Console-Account", ACCOUNT).defaultHeader("X-Console-User", "dana")
                .defaultHeader("X-Console-Roles", "account-admin").build();
    }

    private WebTestClient other() {
        return web.mutate().defaultHeader("X-Console-Account", OTHER).defaultHeader("X-Console-User", "eve").build();
    }

    private WebTestClient service() {
        return web.mutate().defaultHeader("X-Console-Service", "console-bff").build();
    }

    private static ResourceSnapshot resource(String wfrn, String type, String service, Map<String, Object> attributes, Map<String, String> tags,
            String created) {
        Instant at = Instant.parse(created);
        return new ResourceSnapshot(wfrn, ACCOUNT, "global", type, service, "active", 1, tags, attributes, Map.of(), at, at, null, false);
    }
}
