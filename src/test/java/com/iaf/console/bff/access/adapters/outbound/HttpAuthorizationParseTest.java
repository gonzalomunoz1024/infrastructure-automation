package com.iaf.console.bff.access.adapters.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iaf.console.bff.access.domain.AccessCheck;
import com.iaf.console.bff.access.domain.Decisions;

class HttpAuthorizationParseTest {

    @Test
    void aDecisionsScopeAndGuardrailReachTheDenialAndAnElevatedAllowIsMarked() throws Exception {
        String answer = """
                {"decisions": [
                  {"allowed": false, "reason": "the guardrail no-deletes refuses compute:delete", "scope": "guardrail", "guardrailId": "gr_1",
                   "statement": "NoDeletes", "requestable": false},
                  {"allowed": false, "reason": "No statement allows iam:create", "role": "operator", "policyId": "managed/operator", "scope": "policy",
                   "requestable": true, "suggestedRole": "account-admin"},
                  {"allowed": true, "elevated": true},
                  {"allowed": true},
                  {"allowed": false, "scope": "boundary", "policyId": "pol_boundary", "requestable": true}
                ], "version": 7}""";
        List<AccessCheck> checks = List.of(new AccessCheck("compute:delete", "a"), new AccessCheck("iam:create", "b"),
                new AccessCheck("compute:create", "c"), new AccessCheck("compute:get", "d"), new AccessCheck("iam:update", "e"));
        Decisions decisions = HttpAuthorization.parse(new ObjectMapper().readTree(answer), checks);
        assertEquals(7, decisions.version());
        var guardrail = decisions.items().get(0).denial();
        assertEquals("guardrail", guardrail.refusedBy());
        assertEquals("gr_1", guardrail.guardrailId());
        assertFalse(guardrail.requestable());
        assertNull(guardrail.suggestedRole());
        var policy = decisions.items().get(1).denial();
        assertEquals("policy", policy.refusedBy());
        assertTrue(policy.requestable());
        assertEquals("account-admin", policy.suggestedRole());
        assertTrue(decisions.items().get(2).allowed() && decisions.items().get(2).elevated());
        assertFalse(decisions.items().get(3).elevated());
        var boundary = decisions.items().get(4).denial();
        assertEquals("boundary", boundary.refusedBy());
        assertFalse(boundary.requestable(), "asking for a role does not get past a boundary");
    }
}
