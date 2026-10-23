package com.iaf.console.bff.cost.application;

import static com.iaf.console.bff.cost.CostFixtures.COMPUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.cost.CostFixtures;
import com.iaf.console.bff.cost.CostFixtures.Answer;
import com.iaf.console.bff.cost.CostFixtures.Stub;
import com.iaf.console.bff.cost.adapters.outbound.http.HttpCostPort;
import com.iaf.console.bff.cost.domain.BudgetCheck;
import com.iaf.console.bff.cost.domain.BudgetRule;
import com.iaf.console.bff.cost.domain.Estimate;

/**
 * The budget check and the estimate over HTTP: asked with the service token, read as the cost
 * service answers them, and failing open (no rule, no estimate, nothing denied) when the service is
 * down, slow or answers an error.
 */
class BudgetGuardTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BudgetCheck CHECK = new BudgetCheck(COMPUTE, "vm", "compute", Map.of("owner", "team-compute"), new BigDecimal("38.5"));

    private static BudgetGuard guard(String url, Duration within) {
        return new BudgetGuard(new HttpCostPort(CostFixtures.client(url, "console-cost-service", Duration.ofSeconds(10)), JSON), within);
    }

    @Test
    void theCheckGoesWithTheServiceTokenAndItsRulesComeBack() throws Exception {
        String rules = "{\"decision\":\"deny\",\"rules\":[{\"id\":\"budget.exceeded\",\"result\":\"deny\",\"message\":\"over\","
                + "\"budgetId\":\"bud_01jabcdefghjkmnpqrstvwxyz0\",\"budgetName\":\"Compute\",\"percent\":120.0}]}";
        try (Stub cost = new Stub(seen -> Answer.json(200, rules))) {
            List<BudgetRule> answered = guard(cost.url(), Duration.ofSeconds(2)).check(CHECK).block();
            assertEquals(1, answered.size());
            assertTrue(answered.get(0).denies());
            assertEquals("bud_01jabcdefghjkmnpqrstvwxyz0", answered.get(0).budgetId());
            CostFixtures.Seen call = cost.seen.get(0);
            assertEquals("POST", call.method());
            assertEquals("/v1/budgets:check", call.path());
            JsonNode body = JSON.readTree(call.body());
            assertEquals(COMPUTE, body.path("accountId").asText());
            assertEquals("vm", body.path("type").asText());
            assertEquals("compute", body.path("service").asText());
            assertEquals("team-compute", body.path("tags").path("owner").asText());
            assertEquals(38.5, body.path("monthly").asDouble());
            assertFalse(CostsTest.claims(call.headers().get("authorization")).has("acct"), "the service token");
        }
    }

    @Test
    void theCheckFailsOpenWhenTheCostServiceIsDown() {
        assertEquals(List.of(), guard(CostFixtures.nothingListening(), Duration.ofSeconds(2)).check(CHECK).block());
        assertEquals(Optional.empty(), guard(CostFixtures.nothingListening(), Duration.ofSeconds(2)).estimate("vm", "global", Map.of(), 1).block());
    }

    @Test
    void theCheckFailsOpenWhenTheCostServiceIsSlowOrFails() {
        try (Stub slow = new Stub(seen -> new Answer(200, "application/json", "{\"decision\":\"deny\",\"rules\":[]}", Duration.ofSeconds(3)))) {
            long started = System.nanoTime();
            assertEquals(List.of(), guard(slow.url(), Duration.ofMillis(300)).check(CHECK).block());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0, "the check gives up at its own timeout");
        }
        try (Stub failing = new Stub(seen -> Answer.json(500, "{\"error\":\"boom\"}"))) {
            assertEquals(List.of(), guard(failing.url(), Duration.ofSeconds(2)).check(CHECK).block());
        }
    }

    @Test
    void theEstimateIsTheListPriceAndEmptyWhenUnpriced() throws Exception {
        try (Stub cost = new Stub(seen -> seen.body().contains("\"vm\"")
                ? Answer.json(200, "{\"type\":\"vm\",\"monthly\":77.0,\"unitMonthly\":77.0,\"currency\":\"USD\",\"source\":\"list-prices\",\"detail\":\"cpu 4 at $77.00 a month\"}")
                : Answer.json(200, "{\"type\":\"volume\",\"monthly\":0,\"currency\":\"USD\",\"source\":\"unpriced\",\"detail\":\"No list price\"}"))) {
            Estimate vm = guard(cost.url(), Duration.ofSeconds(2)).estimate("vm", "global", Map.of("cpu", 4), 1).block().orElseThrow();
            assertEquals(new BigDecimal("77.0"), vm.monthly().amount());
            assertEquals("cpu 4 at $77.00 a month", vm.detail());
            JsonNode sent = JSON.readTree(cost.seen.get(0).body());
            assertEquals(4, sent.path("attributes").path("cpu").asInt());
            assertEquals(1, sent.path("count").asInt());
            assertEquals(Optional.empty(), guard(cost.url(), Duration.ofSeconds(2)).estimate("volume", "global", Map.of(), 1).block());
        }
    }
}
