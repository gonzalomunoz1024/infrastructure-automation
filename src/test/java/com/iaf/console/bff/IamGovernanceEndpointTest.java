package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

/** Decision 34: the identity service's phase 7 and 9 collections through the IAM pass-through. */
class IamGovernanceEndpointTest extends DemoApp {

    @Test
    void theRulesAreReadByAnyoneSignedInAndNothingElseIsServedThere() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply elevationRules = console.get("/console/v1/iam/elevation-rules", mia);
        assertEquals(200, elevationRules.status(), elevationRules.body());
        assertEquals(240, elevationRules.json().path("breakGlassMaxMinutes").asInt(), elevationRules.body());
        Console.Reply quotaRules = console.get("/console/v1/iam/quota-rules", mia);
        assertEquals(200, quotaRules.status(), quotaRules.body());
        assertEquals("platform-team", quotaRules.json().path("approver").asText(), quotaRules.body());
        assertEquals(405, console.post("/console/v1/iam/elevation-rules", mia, Map.of()).status());
        assertEquals(405, console.post("/console/v1/iam/guardrails", mia, Map.of("name", "x")).status(), "the account's guardrails are read only");
        assertEquals(404, console.get("/console/v1/iam/guardrails/gr_1", mia).status());
        assertEquals(405, console.get("/console/v1/iam/policy-simulations", mia).status());
        assertEquals(404, console.post("/console/v1/iam/elevations/elev_1:extend", mia, Map.of()).status());
    }

    @Test
    void theSimulatorAnswersWhatAPersonMayDo() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply simulated = console.post("/console/v1/iam/policy-simulations", gonzalo, Map.of("principal", Map.of("userId", "user_mia"),
                "checks", List.of(Map.of("action", "compute:delete", "resource", "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-1"),
                        Map.of("action", "compute:get", "resource", "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-1"))));
        assertEquals(200, simulated.status(), simulated.body());
        JsonNode results = simulated.json().path("results");
        assertFalse(results.get(0).path("allowed").asBoolean(true), simulated.body());
        assertEquals("account-admin", results.get(0).path("suggestedRole").asText(), simulated.body());
        assertTrue(results.get(1).path("allowed").asBoolean(false), simulated.body());
    }

    @Test
    void anAccessReviewIsStartedDecidedAndCancelled() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        assertEquals(200, console.put("/console/v1/session", gonzalo, Map.of("accountId", DATA)).status());
        Console.Reply started = console.post("/console/v1/iam/access-reviews", gonzalo, Map.of("name", "Data, October", "dueInDays", 7));
        assertEquals(201, started.status(), started.body());
        String reviewId = started.json().path("reviewId").asText();
        try {
            assertEquals("open", started.json().path("status").asText());
            Console.Reply second = console.post("/console/v1/iam/access-reviews", gonzalo, Map.of());
            assertEquals(409, second.status(), second.body());
            assertEquals(reviewId, second.json().path("detail").path("reviewId").asText(), "the conflict names the open campaign: " + second.body());
            assertEquals(200, console.get("/console/v1/iam/access-reviews/" + reviewId, gonzalo).status());
            Console.Reply items = console.get("/console/v1/iam/access-reviews/" + reviewId + "/items?decision=pending", gonzalo);
            assertEquals(200, items.status(), items.body());
            String own = null;
            String rajs = null;
            for (JsonNode item : items.json().path("items")) {
                if ("user_gonzalo".equals(item.path("userId").asText()) && own == null) {
                    own = item.path("itemId").asText();
                }
                if ("user_raj".equals(item.path("userId").asText()) && "members".equals(item.path("groupName").asText())) {
                    rajs = item.path("itemId").asText();
                }
            }
            assertTrue(own != null && rajs != null, items.body());

            Console.Reply self = console.post("/console/v1/iam/access-reviews/" + reviewId + "/items/" + own + ":decide", gonzalo, Map.of("decision", "keep"));
            assertEquals(403, self.status(), self.body());
            JsonNode denied = self.json().path("denied");
            assertEquals("nobody reviews their own access", denied.path("reason").asText(), self.body());
            assertEquals("policy", denied.path("refusedBy").asText(), "the identity service's scope, named refusedBy: " + self.body());
            assertTrue(denied.path("scope").isMissingNode(), self.body());
            assertFalse(denied.path("requestable").asBoolean(true), self.body());

            Console.Reply kept = console.post("/console/v1/iam/access-reviews/" + reviewId + "/items/" + rajs + ":decide", gonzalo,
                    Map.of("decision", "keep", "comment", "still on the data team"));
            assertEquals(200, kept.status(), kept.body());
            assertEquals("keep", kept.json().path("decision").asText());
            assertEquals("user_gonzalo", kept.json().path("decidedBy").asText());

            Console.Signed ana = console.signIn("ana");
            Console.Reply refused = console.get("/console/v1/iam/access-reviews", ana);
            assertEquals(403, refused.status(), "an operator does not read the account's reviews: " + refused.body());
            assertEquals("access-review:list", refused.json().path("denied").path("action").asText(), refused.body());
            assertEquals("auditor", refused.json().path("denied").path("suggestedRole").asText(), refused.body());
        } finally {
            Console.Reply cancelled = console.post("/console/v1/iam/access-reviews/" + reviewId + ":cancel", gonzalo, null);
            assertEquals(200, cancelled.status(), cancelled.body());
            assertEquals("cancelled", cancelled.json().path("status").asText());
        }
        assertEquals(409, console.post("/console/v1/iam/access-reviews/" + reviewId + ":cancel", gonzalo, null).status());
    }

    @Test
    void aRuleApprovesAnElevationAtOnceAndTheNextSignInHoldsTheRole() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply asked = console.post("/console/v1/iam/elevations", mia, Map.of("role", "operator", "reason", "on call tonight", "durationMinutes", 60));
        assertEquals(201, asked.status(), asked.body());
        String elevationId = asked.json().path("elevationId").asText();
        try {
            assertEquals("active", asked.json().path("status").asText(), asked.body());
            assertEquals("rule:viewer-to-operator", asked.json().path("decidedBy").asText(), asked.body());
            Console.Reply again = console.post("/console/v1/iam/elevations", mia, Map.of("role", "operator", "reason", "again"));
            assertEquals(409, again.status(), again.body());
            assertEquals(elevationId, again.json().path("elevationId").asText(), "the open one: " + again.body());
            Console.Signed elevated = console.signIn("mia");
            assertTrue(elevated.view().path("account").path("roles").toString().contains("operator"), elevated.view().toString());
            Console.Reply own = console.get("/console/v1/iam/elevations", mia);
            assertEquals(200, own.status(), "a person sees their own elevations: " + own.body());
            assertTrue(own.json().path("items").toString().contains(elevationId));
        } finally {
            Console.Reply revoked = console.post("/console/v1/iam/elevations/" + elevationId + ":revoke", mia, Map.of("comment", "done"));
            assertEquals(200, revoked.status(), revoked.body());
            assertEquals("revoked", revoked.json().path("status").asText());
        }
        assertFalse(console.signIn("mia").view().path("account").path("roles").toString().contains("operator"));
    }

    @Test
    void anAskNoRuleApprovesWaitsForAnAdminAndNobodyDecidesTheirOwn() {
        Console.Signed mateo = console.signIn("mateo");
        Console.Reply asked = console.post("/console/v1/iam/elevations", mateo, Map.of("role", "admin", "reason", "rotate the prod keys"));
        assertEquals(201, asked.status(), asked.body());
        String elevationId = asked.json().path("elevationId").asText();
        try {
            assertEquals("pending", asked.json().path("status").asText(), asked.body());
            assertEquals("account-admin", asked.json().path("role").asText(), "admin reads as account-admin");
            assertTrue(asked.json().path("approvers").toString().contains("user_gonzalo"), asked.body());
            Console.Reply own = console.post("/console/v1/iam/elevations/" + elevationId + ":decide", mateo, Map.of("decision", "approve"));
            assertEquals(403, own.status(), own.body());
            assertFalse(own.json().path("denied").path("requestable").asBoolean(true), own.body());
            Console.Reply operator = console.post("/console/v1/iam/elevations/" + elevationId + ":decide", console.signIn("dana"),
                    Map.of("decision", "approve"));
            assertEquals(403, operator.status(), operator.body());
            assertEquals("elevation:decide", operator.json().path("denied").path("action").asText(), operator.body());
            assertEquals("account-admin", operator.json().path("denied").path("suggestedRole").asText(), operator.body());
            Console.Signed gonzalo = console.signIn("gonzalo");
            Console.Reply listed = console.get("/console/v1/iam/elevations?status=pending", gonzalo);
            assertTrue(listed.json().path("items").toString().contains(elevationId), "an admin sees the account's: " + listed.body());
            Console.Reply approved = console.post("/console/v1/iam/elevations/" + elevationId + ":decide", gonzalo,
                    Map.of("decision", "approve", "comment", "for an hour"));
            assertEquals(200, approved.status(), approved.body());
            assertEquals("active", approved.json().path("status").asText());
            assertEquals(200, console.get("/console/v1/iam/elevations/" + elevationId, mateo).status());
        } finally {
            Console.Reply ended = console.send(HttpMethod.POST, "/console/v1/iam/elevations/" + elevationId + ":revoke", mateo, null, Map.of());
            assertEquals(200, ended.status(), ended.body());
        }
    }
}
