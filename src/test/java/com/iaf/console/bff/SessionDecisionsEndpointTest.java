package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** The session view decides the account's own console actions, so the console greys IAM writes and the access-request queue without guessing from roles. */
class SessionDecisionsEndpointTest extends DemoApp {

    private static final List<String> KEYS = List.of("user:create", "user:update", "user:delete", "group:create", "group:update", "group:delete",
            "role:create", "role:update", "role:delete", "policy:create", "policy:update", "policy:delete", "access-key:create", "access-key:update",
            "access-key:delete", "quota:request-increase", "access-request:create", "access-request:list", "access-request:decide");

    @Test
    void anAdminMayDoEveryAccountAction() {
        JsonNode decisions = console.signIn("gonzalo").view().path("decisions");
        assertEquals(KEYS, keys(decisions));
        for (String key : KEYS) {
            assertTrue(decisions.path(key).path("allowed").asBoolean(), key + ": " + decisions.path(key));
            assertTrue(decisions.path(key).path("denied").isMissingNode(), key + " carries no denial when allowed");
        }
    }

    @Test
    void anOperatorWhoApprovesReadsAndDecidesTheQueueButWritesNoIam() {
        Console.Signed dana = console.signIn("dana");
        JsonNode decisions = dana.view().path("decisions");
        JsonNode create = decisions.path("user:create");
        assertFalse(create.path("allowed").asBoolean(true), create.toString());
        JsonNode denied = create.path("denied");
        assertEquals("console-iam", denied.path("layer").asText());
        assertEquals("iam:create", denied.path("action").asText());
        assertEquals("wfrn:wf:iam::" + COMPUTE + ":user/*", denied.path("resource").asText());
        assertEquals("operator", denied.path("role").asText());
        assertEquals("account-admin", denied.path("suggestedRole").asText());
        assertTrue(denied.path("requestable").asBoolean());
        assertFalse(decisions.path("access-key:delete").path("allowed").asBoolean(true));
        assertTrue(decisions.path("access-request:list").path("allowed").asBoolean(), decisions.toString());
        assertTrue(decisions.path("access-request:decide").path("allowed").asBoolean(), decisions.toString());
        assertTrue(decisions.path("access-request:create").path("allowed").asBoolean());
        assertTrue(decisions.path("quota:request-increase").path("allowed").asBoolean());
        assertEquals(200, console.get("/console/v1/access-requests?requester=~someone-else", dana).status(), "the decision agrees with the queue");
    }

    @Test
    void anOperatorReadsOnlyTheirOwnRequests() {
        Console.Signed mateo = console.signIn("mateo");
        JsonNode decisions = mateo.view().path("decisions");
        assertFalse(decisions.path("access-request:list").path("allowed").asBoolean(true), decisions.toString());
        assertEquals("auditor", decisions.path("access-request:list").path("denied").path("suggestedRole").asText());
        assertFalse(decisions.path("access-request:decide").path("allowed").asBoolean(true));
        assertTrue(decisions.path("access-request:create").path("allowed").asBoolean());
        assertEquals(403, console.get("/console/v1/access-requests?requester=~someone-else", mateo).status(), "the decision agrees with the queue");
    }

    @Test
    void aViewerWhoApprovesSeesTheQueueAndMayAskForMore() {
        JsonNode decisions = console.signIn("mia").view().path("decisions");
        assertTrue(decisions.path("access-request:list").path("allowed").asBoolean(), decisions.toString());
        assertTrue(decisions.path("access-request:decide").path("allowed").asBoolean());
        assertTrue(decisions.path("access-request:create").path("allowed").asBoolean());
        assertTrue(decisions.path("quota:request-increase").path("allowed").asBoolean());
        assertEquals("viewer", decisions.path("group:update").path("denied").path("role").asText(), decisions.path("group:update").toString());
    }

    @Test
    void aSwitchAnswersTheNewAccountsDecisions() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply switched = console.put("/console/v1/session", gonzalo, Map.of("accountId", DATA));
        assertEquals(200, switched.status(), switched.body());
        assertEquals(DATA, switched.json().path("account").path("accountId").asText());
        assertEquals(KEYS, keys(switched.json().path("decisions")));
        assertTrue(switched.json().path("decisions").path("access-request:decide").path("allowed").asBoolean());
    }

    private static List<String> keys(JsonNode decisions) {
        List<String> keys = new ArrayList<>();
        decisions.fieldNames().forEachRemaining(keys::add);
        return keys;
    }
}
