package com.iaf.console.bff.live.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.kit.events.EventEnvelope;

class ConsoleEventsIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static EventEnvelope event(String type, String aggregate) {
        return new EventEnvelope("1.0", 1, "evt_1", type + ":1", null, type, Instant.parse("2026-10-23T14:00:00Z"), aggregate, "x1",
                "console-identity-service", null, new EventEnvelope.Context("123456789012", null, null, null), null,
                JSON.createObjectNode().put("version", 1).put("action", "created"));
    }

    @Test
    void guardrailsAccessReviewsAndElevationsChangeWhoMayDoWhat() {
        assertTrue(ConsoleEvents.changesIdentity(event("ElevationChanged", "elevation")));
        assertTrue(ConsoleEvents.changesIdentity(event("GuardrailChanged", "guardrail")));
        assertTrue(ConsoleEvents.changesIdentity(event("AccessReviewChanged", "access-review")));
        assertTrue(ConsoleEvents.changesIdentity(event("UserChanged", "user")));
        assertFalse(ConsoleEvents.changesIdentity(event("BudgetChanged", "budget")));
    }
}
