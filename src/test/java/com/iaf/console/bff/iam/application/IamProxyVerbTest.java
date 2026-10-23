package com.iaf.console.bff.iam.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class IamProxyVerbTest {

    @Test
    void theVerbOfADeniedCallNamesWhatWasTried() {
        assertEquals("list", IamProxy.verbOf(HttpMethod.GET, "users"));
        assertEquals("get", IamProxy.verbOf(HttpMethod.GET, "users/user_mia"));
        assertEquals("create", IamProxy.verbOf(HttpMethod.POST, "users"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.PATCH, "users/user_mia"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.PUT, "quotas/vm.count"));
        assertEquals("delete", IamProxy.verbOf(HttpMethod.DELETE, "groups/g1"));
        assertEquals("rotate", IamProxy.verbOf(HttpMethod.POST, "access-keys/ck_1:rotate"));
        assertEquals("decide", IamProxy.verbOf(HttpMethod.POST, "access-requests/areq_1:decide"));
        assertEquals("update", IamProxy.verbOf(HttpMethod.POST, "quotas/vm.count:request-increase"));
    }

    @Test
    void theActionAndResourceOfADeniedCallAreTheIdentityServicesNames() {
        assertEquals("iam:create", IamProxy.actionOf(HttpMethod.POST, "users"));
        assertEquals("iam:rotate", IamProxy.actionOf(HttpMethod.POST, "access-keys/ck_1:rotate"));
        assertEquals("quota:request-increase", IamProxy.actionOf(HttpMethod.POST, "quotas/resources.vm:request-increase"));
        assertEquals("quota:set", IamProxy.actionOf(HttpMethod.PUT, "quotas/access-keys"));
        assertEquals("quota:set", IamProxy.actionOf(HttpMethod.DELETE, "quota-requests/qreq_1"));
        assertEquals("quota:list", IamProxy.actionOf(HttpMethod.GET, "quotas"));
        assertEquals("access-request:decide", IamProxy.actionOf(HttpMethod.POST, "access-requests/areq_1:decide"));
        assertEquals("access-request:list", IamProxy.actionOf(HttpMethod.GET, "access-requests?status=pending"));
        assertEquals("wfrn:wf:iam::123456789012:user/*", IamProxy.resourceOf("123456789012", "users"));
        assertEquals("wfrn:wf:iam::123456789012:policy/managed", IamProxy.resourceOf("123456789012", "policies/managed"));
        assertEquals("wfrn:wf:iam::123456789012:access-key/ck_1", IamProxy.resourceOf("123456789012", "access-keys/ck_1:rotate"));
        assertEquals("wfrn:wf:iam::123456789012:quota/resources.vm", IamProxy.resourceOf("123456789012", "quotas/resources.vm:request-increase"));
        assertEquals("wfrn:wf:iam::123456789012:access-request/*", IamProxy.resourceOf("123456789012", "access-requests?status=pending"));
    }

    @Test
    void decisionThirtyFoursCallsAreNamedAsTheIdentityServiceNamesThem() {
        assertEquals("cancel", IamProxy.verbOf(HttpMethod.POST, "access-reviews/arev_1:cancel"));
        assertEquals("revoke", IamProxy.verbOf(HttpMethod.POST, "elevations/elev_1:revoke"));
        assertEquals("access-review:list", IamProxy.actionOf(HttpMethod.GET, "access-reviews"));
        assertEquals("access-review:create", IamProxy.actionOf(HttpMethod.POST, "access-reviews"));
        assertEquals("access-review:get", IamProxy.actionOf(HttpMethod.GET, "access-reviews/arev_1/items"), "reading the items is reading the campaign");
        assertEquals("access-review:decide", IamProxy.actionOf(HttpMethod.POST, "access-reviews/arev_1/items/arevi_2:decide"));
        assertEquals("access-review:cancel", IamProxy.actionOf(HttpMethod.POST, "access-reviews/arev_1:cancel"));
        assertEquals("elevation:create", IamProxy.actionOf(HttpMethod.POST, "elevations"));
        assertEquals("elevation:decide", IamProxy.actionOf(HttpMethod.POST, "elevations/elev_1:decide"));
        assertEquals("elevation:revoke", IamProxy.actionOf(HttpMethod.POST, "elevations/elev_1:revoke"));
        assertEquals("iam:list", IamProxy.actionOf(HttpMethod.GET, "guardrails"));
        assertEquals("iam:get", IamProxy.actionOf(HttpMethod.POST, "policy-simulations", "user/user_mia"));
        assertEquals("iam:list", IamProxy.actionOf(HttpMethod.POST, "policy-simulations", "group/*"));
        assertEquals("wfrn:wf:iam::123456789012:access-review/arev_1", IamProxy.resourceOf("123456789012", "access-reviews/arev_1/items/arevi_2:decide"));
        assertEquals("wfrn:wf:iam::123456789012:access-review/arev_1", IamProxy.resourceOf("123456789012", "access-reviews/arev_1:cancel"));
        assertEquals("wfrn:wf:iam::123456789012:elevation/*", IamProxy.resourceOf("123456789012", "elevations"));
        assertEquals("wfrn:wf:iam::123456789012:guardrail/*", IamProxy.resourceOf("123456789012", "guardrails"));
        assertEquals("wfrn:wf:iam::123456789012:role/operator", IamProxy.resourceOf("123456789012", "policy-simulations", "role/operator"));
    }

    @Test
    void anIdentityDenialsScopeIsNamedRefusedByAndABoundaryOrGuardrailIsNeverRequestable() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObjectNode guardrail = (ObjectNode) json.readTree("{\"error\":\"no\",\"denied\":{\"layer\":\"console-iam\",\"action\":\"compute:delete\","
                + "\"scope\":\"guardrail\",\"guardrailId\":\"gr_1\",\"requestable\":true,\"suggestedRole\":\"account-admin\"}}");
        assertTrue(IamProxy.normalized(guardrail));
        JsonNode d = guardrail.path("denied");
        assertEquals("guardrail", d.path("refusedBy").asText());
        assertTrue(d.path("scope").isMissingNode(), "the BFF's scope is a platform scope: " + d);
        assertEquals("gr_1", d.path("guardrailId").asText());
        assertFalse(d.path("requestable").asBoolean(true));
        assertTrue(d.path("suggestedRole").isMissingNode(), d.toString());
        ObjectNode policy = (ObjectNode) json.readTree("{\"denied\":{\"layer\":\"console-iam\",\"action\":\"iam:create\",\"scope\":\"policy\","
                + "\"requestable\":true,\"suggestedRole\":\"account-admin\"}}");
        assertTrue(IamProxy.normalized(policy));
        assertEquals("policy", policy.path("denied").path("refusedBy").asText());
        assertTrue(policy.path("denied").path("requestable").asBoolean(false), "a policy's refusal stays requestable");
        assertEquals("account-admin", policy.path("denied").path("suggestedRole").asText());
        ObjectNode boundary = (ObjectNode) json.readTree("{\"denied\":{\"layer\":\"console-iam\",\"action\":\"iam:create\",\"scope\":\"boundary\","
                + "\"policyId\":\"pol_b\",\"requestable\":true}}");
        assertTrue(IamProxy.normalized(boundary));
        assertFalse(boundary.path("denied").path("requestable").asBoolean(true));
        ObjectNode platform = (ObjectNode) json.readTree("{\"denied\":{\"layer\":\"platform\",\"action\":\"compute:delete\",\"scope\":\"workflows:run\"}}");
        assertFalse(IamProxy.normalized(platform), "a platform scope stays as it is");
        assertEquals("workflows:run", platform.path("denied").path("scope").asText());
    }
}
