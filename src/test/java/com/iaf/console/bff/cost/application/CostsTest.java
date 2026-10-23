package com.iaf.console.bff.cost.application;

import static com.iaf.console.bff.cost.CostFixtures.COMPUTE;
import static com.iaf.console.bff.cost.CostFixtures.DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.common.relay.Relayed;
import com.iaf.console.bff.cost.CostFixtures;
import com.iaf.console.bff.cost.CostFixtures.Answer;
import com.iaf.console.bff.cost.CostFixtures.Stub;
import com.iaf.console.bff.cost.adapters.outbound.http.HttpCostPort;
import com.iaf.console.bff.cost.domain.Money;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.kit.errors.InvalidInputException;

/**
 * The cost slice against a stub cost service: the organization view decides {@code cost:list} in
 * every account and asks once, with the service token, for the allowed ones only; the person's own
 * account goes as the person; budgets gain {@code can}; and a resource's monthly cost for the
 * copilot is empty, never an error, when refused, unknown or unreachable.
 */
class CostsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VM = "wfrn:wf:compute:global:" + COMPUTE + ":vm/web-01";
    private static final String BUDGET = "bud_01jabcdefghjkmnpqrstvwxyz0";

    private static Costs costs(String url, BiPredicate<String, String> allows) {
        HttpCostPort port = new HttpCostPort(CostFixtures.client(url, "console-cost-service", Duration.ofSeconds(2)), JSON);
        return new Costs(CostFixtures.access(allows), CostFixtures.sessions(), platform(), port, JSON);
    }

    static PlatformProperties platform() {
        return new PlatformProperties("http://platform.test", "", "wf", Duration.ofSeconds(5), Duration.ofMinutes(10), Map.of(), null);
    }

    @Test
    void theOrganizationViewReadsOnlyTheAllowedAccountsWithTheServiceTokenAndNamesTheRest() throws Exception {
        try (Stub cost = new Stub(seen -> Answer.json(200, "{\"currency\":\"USD\",\"total\":12.5,\"groups\":[]}"))) {
            Costs costs = costs(cost.url(), (account, action) -> COMPUTE.equals(account) && "cost:list".equals(action));
            List<Map.Entry<String, String>> params = List.of(Map.entry("scope", "organization"), Map.entry("from", "2026-10-01"),
                    Map.entry("to", "2026-10-23"), Map.entry("groupBy", "account"), Map.entry("accountId", "999999999999"));
            Relayed answer = costs.report(CostFixtures.session(), "organization", params).block();

            assertEquals(200, answer.status());
            JsonNode body = JSON.readTree(answer.body());
            assertEquals("[\"" + DATA + "\"]", body.path("skippedAccounts").toString(), "the refused account is named, never read");
            assertEquals(12.5, body.path("total").asDouble());
            CostFixtures.Seen call = cost.seen.get(0);
            assertEquals("/v1/cost", call.path());
            assertTrue(call.rawQuery().contains("accountId=" + COMPUTE), call.rawQuery());
            assertFalse(call.rawQuery().contains(DATA), "a refused account is not asked for: " + call.rawQuery());
            assertFalse(call.rawQuery().contains("999999999999"), "the browser never names the accounts: " + call.rawQuery());
            assertFalse(call.rawQuery().contains("scope="), call.rawQuery());
            assertTrue(call.rawQuery().contains("groupBy=account"), call.rawQuery());
            JsonNode claims = claims(call.headers().get("authorization"));
            assertFalse(claims.has("acct"), "the organization view goes with the service token: " + claims);
            assertEquals(1, cost.seen.size(), "one call for every allowed account");
        }
    }

    @Test
    void theAccountViewGoesAsThePersonAndARefusalIsA403() throws Exception {
        try (Stub cost = new Stub(seen -> Answer.json(200, "{\"currency\":\"USD\",\"total\":3}"))) {
            Relayed answer = costs(cost.url(), (account, action) -> true).report(CostFixtures.session(), null,
                    List.of(Map.entry("from", "2026-10-01"), Map.entry("to", "2026-10-02"))).block();
            assertEquals(200, answer.status());
            assertFalse(JSON.readTree(answer.body()).has("skippedAccounts"));
            JsonNode claims = claims(cost.seen.get(0).headers().get("authorization"));
            assertEquals(COMPUTE, claims.path("acct").asText(), claims.toString());
            assertEquals("user_gonzalo", claims.path("sub").asText());

            Costs refusing = costs(cost.url(), (account, action) -> false);
            DeniedException denied = assertThrows(DeniedException.class, () -> refusing.report(CostFixtures.session(), "account", List.of()).block());
            assertEquals("cost:list", denied.denial().action());
            assertEquals("wfrn:wf:cost::" + COMPUTE + ":*", denied.denial().resource());
            assertThrows(DeniedException.class, () -> refusing.report(CostFixtures.session(), "organization", List.of()).block(),
                    "no account allowed: refused, not an empty report");
            assertThrows(InvalidInputException.class, () -> refusing.report(CostFixtures.session(), "galaxy", List.of()).block());
            assertEquals(1, cost.seen.size(), "nothing is asked of the cost service once refused");
        }
    }

    @Test
    void theChargebackCsvNeedsCostExportAndNamesTheSkippedAccounts() {
        try (Stub cost = new Stub(seen -> new Answer(200, "text/csv", "month,costCentre\n2026-10,cc-1001\n", Duration.ZERO))) {
            Costs costs = costs(cost.url(), (account, action) -> COMPUTE.equals(account) && "cost:export".equals(action));
            Relayed csv = costs.chargeback(CostFixtures.session(), "organization", List.of(Map.entry("month", "2026-10")), true).block();
            assertEquals(200, csv.status());
            assertEquals(DATA, csv.header(Costs.SKIPPED_HEADER));
            assertEquals("/v1/chargeback.csv", cost.seen.get(0).path());

            Costs listOnly = costs(cost.url(), (account, action) -> "cost:list".equals(action));
            assertEquals(200, listOnly.chargeback(CostFixtures.session(), "account", List.of(Map.entry("month", "2026-10")), false).block().status(),
                    "the report is read like cost");
            DeniedException denied = assertThrows(DeniedException.class,
                    () -> listOnly.chargeback(CostFixtures.session(), "account", List.of(Map.entry("month", "2026-10")), true).block());
            assertEquals("cost:export", denied.denial().action());
        }
    }

    @Test
    void eachBudgetCarriesWhatThePersonMayDoWithIt() throws Exception {
        String page = "{\"items\":[{\"budgetId\":\"" + BUDGET + "\",\"name\":\"Compute\"}],\"next\":null}";
        try (Stub cost = new Stub(seen -> Answer.json(200, page))) {
            Costs costs = costs(cost.url(), (account, action) -> !"budgets:delete".equals(action));
            JsonNode body = JSON.readTree(costs.budgets(CostFixtures.session(), 20, null).block().body());
            JsonNode can = body.path("items").get(0).path("can");
            assertTrue(can.path("update").asBoolean(), body.toString());
            assertFalse(can.path("delete").asBoolean(), body.toString());
            assertEquals("limit=20", cost.seen.get(0).rawQuery());

            assertThrows(com.iaf.console.kit.errors.NotFoundException.class, () -> costs.budget(CostFixtures.session(), "not-a-budget").block());
            assertThrows(InvalidInputException.class, () -> costs.updateBudget(CostFixtures.session(), BUDGET, null, "{}".getBytes(StandardCharsets.UTF_8)).block(),
                    "a change names the version it was read at");
        }
    }

    @Test
    void aBudgetIsCreatedInTheSessionsAccount() throws Exception {
        try (Stub cost = new Stub(seen -> Answer.json(201, "{\"budgetId\":\"" + BUDGET + "\",\"version\":1}"))) {
            Costs costs = costs(cost.url(), (account, action) -> true);
            Relayed created = costs.createBudget(CostFixtures.session(), "{\"name\":\"All\",\"amount\":100}".getBytes(StandardCharsets.UTF_8), "key-1").block();
            assertEquals(201, created.status());
            JsonNode sent = JSON.readTree(cost.seen.get(0).body());
            assertEquals(COMPUTE, sent.path("scope").path("accountId").asText(), sent.toString());
            assertEquals("key-1", cost.seen.get(0).headers().get("idempotency-key"));
            assertTrue(JSON.readTree(created.body()).path("can").path("delete").asBoolean());

            assertThrows(InvalidInputException.class, () -> costs.createBudget(CostFixtures.session(),
                    ("{\"name\":\"Theirs\",\"amount\":1,\"scope\":{\"accountId\":\"" + DATA + "\"}}").getBytes(StandardCharsets.UTF_8), null).block());
        }
    }

    @Test
    void aResourcesMonthlyCostIsEmptyNeverAnErrorWhenItCannotBeTold() throws Exception {
        try (Stub cost = new Stub(seen -> seen.path().contains("web-01") ? Answer.json(200, "{\"wfrn\":\"" + VM + "\",\"monthly\":38.5,\"currency\":\"USD\"}")
                : Answer.json(404, "{\"error\":\"resource not found\"}"))) {
            Costs costs = costs(cost.url(), (account, action) -> "cost:list".equals(action));
            Optional<Money> money = costs.monthlyCost(CostFixtures.session(), VM).block();
            assertEquals(Optional.of(new Money(new BigDecimal("38.5"), "USD")), money);
            assertEquals("/v1/wfrn/wfrn:wf:compute:global:" + COMPUTE + ":vm%2Fweb-01/cost", cost.seen.get(0).path());
            assertEquals(COMPUTE, claims(cost.seen.get(0).headers().get("authorization")).path("acct").asText(), "as the person");

            assertEquals(Optional.empty(), costs.monthlyCost(CostFixtures.session(), VM.replace("web-01", "gone-01")).block(), "a 404");
            assertEquals(Optional.empty(), costs.monthlyCost(CostFixtures.session(), "wfrn:wf:compute:global:" + DATA + ":vm/web-01").block(),
                    "another account's");
            assertEquals(Optional.empty(), costs.monthlyCost(CostFixtures.session(), "not a wfrn").block());
            int asked = cost.seen.size();
            assertEquals(Optional.empty(), costs(cost.url(), (account, action) -> false).monthlyCost(CostFixtures.session(), VM).block(), "refused");
            assertEquals(asked, cost.seen.size(), "a refusal asks nothing");
        }
        assertEquals(Optional.empty(), costs(CostFixtures.nothingListening(), (account, action) -> true).monthlyCost(CostFixtures.session(), VM).block(),
                "the cost service down");
    }

    /** The claims of a bearer token, unverified. */
    static JsonNode claims(String authorization) throws Exception {
        String jwt = authorization.substring("Bearer ".length());
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }
}
