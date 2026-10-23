package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

/** WP 1.5: the identity service's account resources through the BFF, the Denial on refusals, and access requests. */
class IamProxyTest extends DemoApp {

    @Test
    void anAdminListsTheAccountsUsersGroupsRolesAndPolicies() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply users = console.get("/console/v1/iam/users?limit=50", gonzalo);
        assertEquals(200, users.status(), users.body());
        String body = users.json().path("items").toString();
        assertTrue(body.contains("user_dana") && body.contains("user_mia"), body);
        Console.Reply one = console.get("/console/v1/iam/users/user_mia", gonzalo);
        assertEquals(200, one.status(), one.body());
        assertTrue(one.json().path("roles").toString().contains("viewer"), one.body());
        assertEquals(404, console.get("/console/v1/iam/users/user_raj", gonzalo).status(), "raj is not a member of the compute account");
        Console.Reply groups = console.get("/console/v1/iam/groups", gonzalo);
        assertEquals(200, groups.status(), groups.body());
        assertTrue(groups.json().path("items").toString().contains("owners"));
        Console.Reply roles = console.get("/console/v1/iam/roles", gonzalo);
        assertEquals(200, roles.status(), roles.body());
        assertTrue(roles.json().path("items").toString().contains("operator"));
        Console.Reply policies = console.get("/console/v1/iam/policies", gonzalo);
        assertEquals(200, policies.status(), policies.body());
        assertTrue(policies.json().path("items").size() > 0);
        Console.Reply quotas = console.get("/console/v1/iam/quotas", gonzalo);
        assertEquals(200, quotas.status(), quotas.body());
        List<String> keys = new java.util.ArrayList<>();
        quotas.json().path("items").forEach(q -> keys.add(q.path("key").asText()));
        assertEquals(List.of("jobs-per-minute", "access-keys", "resources.database", "resources.environment", "resources.image", "resources.vm",
                "resources.volume"), keys,
                "the identity service's keys: " + quotas.body());
        JsonNode jobs = quotas.json().path("items").get(0);
        assertEquals("platform", jobs.path("source").asText(), "jobs per minute is the platform's bucket: " + jobs);
        assertEquals(60, jobs.path("burst").asInt(), jobs.toString());
        assertEquals(404, console.get("/console/v1/iam/toasters", gonzalo).status());
    }

    @Test
    void aViewerReadsButEveryWriteIsRefusedWithTheDenial() {
        Console.Signed mia = console.signIn("mia");
        assertEquals(200, console.get("/console/v1/iam/users", mia).status());
        Console.Reply refused = console.post("/console/v1/iam/users", mia, Map.of("userId", "user_noah", "email", "noah@example.com", "groups", List.of("viewers")));
        assertEquals(403, refused.status(), refused.body());
        JsonNode denied = refused.json().path("denied");
        assertEquals("console-iam", denied.path("layer").asText());
        assertTrue(denied.path("action").asText().startsWith("iam:"), denied.toString());
        assertEquals("viewer", denied.path("role").asText());
        assertEquals("account-admin", denied.path("suggestedRole").asText());
        Console.Reply noCsrf = console.send(HttpMethod.POST, "/console/v1/iam/users", mia, Map.of("userId", "user_noah"), Map.of(Console.CSRF_HEADER, ""));
        assertEquals(403, noCsrf.status());
        assertTrue(noCsrf.json().path("denied").isMissingNode());
    }

    @Test
    void anAdminAddsAMemberAndTheGroupsFollow() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply added = console.post("/console/v1/iam/users", gonzalo,
                Map.of("userId", "user_noah", "email", "noah@example.com", "name", "Noah Berg", "groups", List.of("viewers")));
        assertTrue(added.status() == 200 || added.status() == 201, added.body());
        assertTrue(added.json().path("roles").toString().contains("viewer"), added.body());
        Console.Reply updated = console.send(HttpMethod.PATCH, "/console/v1/iam/users/user_noah", gonzalo, Map.of("groups", List.of("members")), Map.of());
        assertEquals(200, updated.status(), updated.body());
        assertTrue(updated.json().path("roles").toString().contains("operator"), updated.body());
        Console.Reply removed = console.send(HttpMethod.DELETE, "/console/v1/iam/users/user_noah", gonzalo, null, Map.of());
        assertTrue(removed.status() == 204 || removed.status() == 200, removed.body());
        assertEquals(404, console.get("/console/v1/iam/users/user_noah", gonzalo).status());
    }

    @Test
    void accessKeysAreCreatedRotatedAndRevoked() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply created = console.post("/console/v1/iam/access-keys", gonzalo, Map.of("description", "ci"));
        assertTrue(created.status() == 200 || created.status() == 201, created.body());
        String keyId = created.json().path("keyId").asText();
        assertTrue(keyId.startsWith("ck_"), created.body());
        assertNotNull(created.json().path("secretPart").asText(null), "the secret part is shown once");
        Console.Reply listed = console.get("/console/v1/iam/access-keys", gonzalo);
        assertTrue(listed.json().path("items").toString().contains(keyId));
        assertFalse(listed.body().contains("cs_"), "the list never shows secrets");
        Console.Reply rotated = console.post("/console/v1/iam/access-keys/" + keyId + ":rotate", gonzalo, null);
        assertEquals(200, rotated.status(), rotated.body());
        Console.Reply revoked = console.send(HttpMethod.DELETE, "/console/v1/iam/access-keys/" + keyId, gonzalo, null, Map.of());
        assertTrue(revoked.status() == 204 || revoked.status() == 200, revoked.body());
    }

    @Test
    void anAccessRequestIsApprovedByAnAdminAndUnlocksTheAction() {
        Console.Signed mateo = console.signIn("mateo");
        JsonNode before = TypesEndpointTest.action(TypesEndpointTest.find(console.get("/console/v1/types", mateo).json(), "compute/vm"), "delete");
        assertFalse(before.path("allowed").asBoolean(true), "an operator may not delete");
        Console.Reply requested = console.post("/console/v1/access-requests", mateo,
                Map.of("action", "compute:delete", "resource", before.path("denied").path("resource").asText(), "reason", "I clean up the dev VMs every Friday"));
        assertEquals(202, requested.status(), requested.body());
        JsonNode request = requested.json();
        String requestId = request.path("requestId").asText();
        assertEquals("pending", request.path("status").asText());
        assertEquals("account-admin", request.path("role").asText());
        assertEquals("user_mateo", request.path("requester").path("userId").asText());
        assertTrue(request.path("approvers").toString().contains("user_gonzalo"), request.toString());
        assertTrue(request.path("approvers").toString().contains("user_dana"), "an approver group's members decide too: " + request);
        assertFalse(request.path("approvers").toString().contains("user_mateo"), request.toString());
        Console.Reply duplicate = console.post("/console/v1/access-requests", mateo, Map.of("action", "compute:terminate", "reason", "the same role again"));
        assertEquals(409, duplicate.status(), duplicate.body());
        assertEquals(requestId, duplicate.json().path("requestId").asText(), "a second request for the same role answers the pending one");
        Console.Reply noReason = console.post("/console/v1/access-requests", mateo, Map.of("action", "compute:patch", "reason", ""));
        assertEquals(400, noReason.status(), noReason.body());
        Console.Reply allowedAlready = console.post("/console/v1/access-requests", mateo, Map.of("action", "compute:patch", "reason", "pls"));
        assertEquals(400, allowedAlready.status(), "an operator may patch already: " + allowedAlready.body());
        assertTrue(console.get("/console/v1/access-requests", mateo).json().path("items").toString().contains(requestId));
        Console.Reply someoneElses = console.get("/console/v1/access-requests?requester=user_gonzalo", mateo);
        assertEquals(403, someoneElses.status(), someoneElses.body());
        assertEquals("auditor", someoneElses.json().path("denied").path("suggestedRole").asText(), someoneElses.body());
        assertEquals(200, console.get("/console/v1/access-requests/" + requestId, mateo).status(), "the requester reads their own request");
        Console.Reply notTheirs = console.get("/console/v1/access-requests/" + requestId, console.signIn("dana"));
        assertEquals(403, notTheirs.status(), "an operator reads no one else's request: " + notTheirs.body());
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply queue = console.get("/console/v1/access-requests?status=pending&requester=user_mateo", gonzalo);
        assertEquals(200, queue.status(), queue.body());
        assertTrue(queue.json().path("items").toString().contains(requestId), queue.body());
        assertEquals(400, console.get("/console/v1/access-requests?status=lost", gonzalo).status());
        assertEquals(200, console.get("/console/v1/access-requests/" + requestId, gonzalo).status(), "an admin reads the queue");
        Console.Reply notAnAdmin = console.post("/console/v1/access-requests/" + requestId + ":decide", mateo, Map.of("decision", "approve"));
        assertEquals(403, notAnAdmin.status(), notAnAdmin.body());
        assertEquals("console-iam", notAnAdmin.json().path("denied").path("layer").asText());
        Console.Reply decided = console.post("/console/v1/access-requests/" + requestId + ":decide", gonzalo, Map.of("decision", "approve", "comment", "ok"));
        assertEquals(202, decided.status(), decided.body());
        assertEquals("approved", decided.json().path("status").asText());
        assertEquals("user_gonzalo", decided.json().path("decidedBy").asText());
        Console.Reply again = console.post("/console/v1/access-requests/" + requestId + ":decide", gonzalo, Map.of("decision", "reject"));
        assertEquals(409, again.status(), "a decided request is not decided twice: " + again.body());
        JsonNode after = TypesEndpointTest.action(TypesEndpointTest.find(console.get("/console/v1/types", mateo).json(), "compute/vm"), "delete");
        assertTrue(after.path("allowed").asBoolean(false), "the cached decision was dropped when the membership changed");
    }

    @Test
    void aQuotaIncreaseIsRequestedFollowedAndWithdrawn() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply opened = console.post("/console/v1/iam/quotas/resources.volume:request-increase", gonzalo,
                Map.of("limit", 400, "reason", "the nightly backups need more volumes"));
        assertEquals(202, opened.status(), opened.body());
        String requestId = opened.json().path("requestId").asText();
        assertTrue(requestId.startsWith("qreq_"), opened.body());
        assertEquals("open", opened.json().path("status").asText());
        assertEquals(400, opened.json().path("requestedLimit").asInt());
        assertEquals(100, opened.json().path("currentLimit").asInt());
        Console.Reply second = console.post("/console/v1/iam/quotas/resources.volume:request-increase", gonzalo,
                Map.of("limit", 500, "reason", "the nightly backups need even more"));
        assertEquals(409, second.status(), "one open request per quota: " + second.body());
        Console.Reply quota = console.get("/console/v1/iam/quotas/resources.volume", gonzalo);
        assertEquals(requestId, quota.json().path("pendingRequestId").asText(), quota.body());
        Console.Reply followed = console.get("/console/v1/iam/quota-requests/" + requestId, gonzalo);
        assertEquals(200, followed.status(), followed.body());
        assertEquals("open", followed.json().path("status").asText());
        Console.Reply decide = console.post("/console/v1/iam/quota-requests/" + requestId + ":decide", gonzalo, Map.of("decision", "approve"));
        assertEquals(404, decide.status(), "only the platform team decides a quota request: " + decide.body());
        Console.Reply notTheirs = console.send(HttpMethod.DELETE, "/console/v1/iam/quota-requests/" + requestId, console.signIn("dana"), null, Map.of());
        assertEquals(403, notTheirs.status(), notTheirs.body());
        Console.Reply withdrawn = console.send(HttpMethod.DELETE, "/console/v1/iam/quota-requests/" + requestId, gonzalo, null, Map.of());
        assertEquals(204, withdrawn.status(), withdrawn.body());
        Console.Reply twice = console.send(HttpMethod.DELETE, "/console/v1/iam/quota-requests/" + requestId, gonzalo, null, Map.of());
        assertEquals(409, twice.status(), twice.body());
        assertEquals("withdrawn", console.get("/console/v1/iam/quota-requests/" + requestId, gonzalo).json().path("status").asText());
        assertTrue(console.get("/console/v1/iam/quotas/resources.volume", gonzalo).json().path("pendingRequestId").isMissingNode());
    }

    @Test
    void aViewerAsksForMoreQuotaAndWithdrawsItThemselves() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply opened = console.post("/console/v1/iam/quotas/access-keys:request-increase", mia,
                Map.of("limit", 80, "reason", "the build farm needs a key per runner"));
        assertEquals(202, opened.status(), "a viewer may ask for a higher quota: " + opened.body());
        assertEquals("user_mia", opened.json().path("requestedBy").asText(), opened.body());
        assertEquals(50, opened.json().path("currentLimit").asInt(), opened.body());
        Console.Reply withdrawn = console.send(HttpMethod.DELETE, "/console/v1/iam/quota-requests/" + opened.json().path("requestId").asText(), mia, null,
                Map.of());
        assertEquals(204, withdrawn.status(), "the requester withdraws their own: " + withdrawn.body());
    }

    @Test
    void nobodyDecidesTheirOwnAccessRequest() {
        Console.Signed dana = console.signIn("dana");
        Console.Reply requested = console.post("/console/v1/access-requests", dana,
                Map.of("action", "compute:delete", "reason", "I retire the old build VMs"));
        assertEquals(202, requested.status(), requested.body());
        String requestId = requested.json().path("requestId").asText();
        assertFalse(requested.json().path("approvers").toString().contains("user_dana"),
                "an approver who asks is no approver of their own request: " + requested.body());
        Console.Reply own = console.post("/console/v1/access-requests/" + requestId + ":decide", dana, Map.of("decision", "approve"));
        assertEquals(403, own.status(), own.body());
        JsonNode denied = own.json().path("denied");
        assertEquals("access-request:decide", denied.path("action").asText(), own.body());
        assertEquals("wfrn:wf:iam::" + COMPUTE + ":access-request/" + requestId, denied.path("resource").asText(), own.body());
        assertFalse(denied.path("requestable").asBoolean(true), "there is nothing to ask for: " + own.body());
        assertEquals("nobody decides their own access request", own.json().path("error").asText(), own.body());
        Console.Reply rejected = console.post("/console/v1/access-requests/" + requestId + ":decide", console.signIn("gonzalo"),
                Map.of("decision", "reject", "comment", "use the cleanup workflow"));
        assertEquals(202, rejected.status(), "someone else decides it: " + rejected.body());
    }
}
