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
        Console.Reply duplicate = console.post("/console/v1/access-requests", mateo,
                Map.of("action", "compute:delete", "reason", "I clean up the dev VMs every Friday"));
        assertEquals(409, duplicate.status(), duplicate.body());
        Console.Reply tooShort = console.post("/console/v1/access-requests", mateo, Map.of("action", "compute:patch", "reason", "pls"));
        assertEquals(400, tooShort.status(), tooShort.body());
        assertTrue(console.get("/console/v1/access-requests", mateo).json().path("items").toString().contains(requestId));
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply forAdmins = console.get("/console/v1/access-requests?scope=account&status=pending", gonzalo);
        assertEquals(200, forAdmins.status(), forAdmins.body());
        assertTrue(forAdmins.json().path("items").toString().contains(requestId), forAdmins.body());
        Console.Reply notAnAdmin = console.post("/console/v1/access-requests/" + requestId + ":decide", mateo, Map.of("decision", "approve"));
        assertEquals(403, notAnAdmin.status(), notAnAdmin.body());
        assertEquals("console-iam", notAnAdmin.json().path("denied").path("layer").asText());
        Console.Reply decided = console.post("/console/v1/access-requests/" + requestId + ":decide", gonzalo, Map.of("decision", "approve", "comment", "ok"));
        assertEquals(202, decided.status(), decided.body());
        assertEquals("approved", decided.json().path("status").asText());
        assertEquals("user_gonzalo", decided.json().path("decidedBy").asText());
        JsonNode after = TypesEndpointTest.action(TypesEndpointTest.find(console.get("/console/v1/types", mateo).json(), "compute/vm"), "delete");
        assertTrue(after.path("allowed").asBoolean(false), "the cached decision was dropped when the membership changed");
    }
}
