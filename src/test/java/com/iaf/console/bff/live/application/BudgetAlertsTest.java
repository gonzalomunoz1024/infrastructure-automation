package com.iaf.console.bff.live.application;

import static com.iaf.console.bff.cost.CostFixtures.COMPUTE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iaf.console.bff.cost.CostFixtures;
import com.iaf.console.bff.live.domain.ConsoleEvents;
import com.iaf.console.bff.live.domain.LiveEvent;
import com.iaf.console.bff.live.domain.LiveHub;
import com.iaf.console.bff.live.domain.PlatformEvents;
import com.iaf.console.kit.events.EventEnvelope;

/**
 * cost.events on the live stream: a threshold crossed becomes {@code budget.alert} for its account
 * with the event's data, a budget change or a chargeback month does not reach the pages, and the
 * alert reaches only the sessions whose person may read the budget ({@code budgets:get}).
 */
class BudgetAlertsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BUDGET = "bud_01jabcdefghjkmnpqrstvwxyz0";

    private static EventEnvelope event(String type, String aggregate) {
        ObjectNode data = JSON.createObjectNode().put("version", 3).put("budgetId", BUDGET).put("name", "Compute").put("period", "2026-10")
                .put("threshold", 80).put("percent", 85.5).put("spent", 85.5).put("amount", 100).put("currency", "USD");
        return new EventEnvelope("1.0", 1, "evt_1", type + ":" + COMPUTE + ":" + BUDGET + ":2026-10-80", null, type, Instant.parse("2026-10-23T14:00:00Z"),
                aggregate, BUDGET, "console-cost-service", null, new EventEnvelope.Context(COMPUTE, null, null, null), null, data);
    }

    @Test
    void aThresholdCrossedIsABudgetAlertOfItsAccount() {
        PlatformEvents.Mapped mapped = ConsoleEvents.toLive(event(ConsoleEvents.BUDGET_THRESHOLD_CROSSED, "budget")).orElseThrow();
        assertEquals(COMPUTE, mapped.accountId());
        assertEquals(LiveEvent.BUDGET_ALERT, mapped.event().type());
        assertEquals(BUDGET, mapped.event().data().path("budgetId").asText());
        assertEquals(80, mapped.event().data().path("threshold").asInt());
        assertEquals(85.5, mapped.event().data().path("percent").asDouble());
        assertEquals("2026-10", mapped.event().data().path("period").asText());
        assertEquals(Optional.empty(), ConsoleEvents.toLive(event("BudgetChanged", "budget")), "a budget change is not an alert");
        assertEquals(Optional.empty(), ConsoleEvents.toLive(event("ChargebackPublished", "chargeback")));
    }

    @Test
    void anAlertReachesOnlyTheSessionsThatMayReadTheBudget() {
        LiveHub hub = new LiveHub(100, Instant::now);
        LiveProperties properties = new LiveProperties(100, Duration.ofSeconds(30), new LiveProperties.Relay(false, 0));
        Live reading = new Live(hub, properties, CostFixtures.access((account, action) -> true), "wf");
        Live refused = new Live(hub, properties, CostFixtures.access((account, action) -> !"budgets:get".equals(action)), "wf");
        PlatformEvents.Mapped alert = ConsoleEvents.toLive(event(ConsoleEvents.BUDGET_THRESHOLD_CROSSED, "budget")).orElseThrow();
        hub.publish(alert.accountId(), alert.event());
        hub.publish(COMPUTE, new LiveEvent(LiveEvent.JOB_UPDATED, null, Instant.now(), null, "job:1", null));

        List<String> seen = reading.stream(CostFixtures.session(), 0L, null, Set.of()).filter(e -> e.event() != null).take(2)
                .map(ServerSentEvent::event).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of(LiveEvent.BUDGET_ALERT, LiveEvent.JOB_UPDATED), seen);

        List<String> other = refused.stream(CostFixtures.session(), 0L, null, Set.of()).filter(e -> e.event() != null).take(1)
                .map(ServerSentEvent::event).collectList().block(Duration.ofSeconds(5));
        assertEquals(List.of(LiveEvent.JOB_UPDATED), other, "without budgets:get the alert is left out, the rest goes on");
        assertTrue(reading.mayRead(CostFixtures.session(), alert.event()).block());
    }
}
