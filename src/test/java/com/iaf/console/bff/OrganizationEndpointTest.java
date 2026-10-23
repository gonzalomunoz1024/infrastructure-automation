package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.session.ports.outbound.SessionStore;

/** Decision 34: the organization admins' pages, called as the console-internal principal for an admin and a 404 for anyone else. */
class OrganizationEndpointTest extends DemoApp {

    static final String SANDBOX = "345678901234";

    @Autowired
    SessionStore store;

    @Test
    void theSessionSaysWhoIsAnOrganizationAdmin() {
        assertTrue(console.signIn("gonzalo").view().path("organizationAdmin").asBoolean(false), "gonzalo's sign-in carries console-org-admins");
        Console.Signed mia = console.signIn("mia");
        assertTrue(mia.view().has("organizationAdmin"), mia.view().toString());
        assertFalse(mia.view().path("organizationAdmin").asBoolean(true));
    }

    @Test
    void anyoneElseGetsANotFoundOnEveryOrganizationPath() {
        Console.Signed dana = console.signIn("dana");
        assertEquals(404, console.get("/console/v1/organization/accounts", dana).status());
        assertEquals(404, console.get("/console/v1/organization/guardrails", dana).status());
        assertEquals(404, console.get("/console/v1/organization/guardrails/gr_x", dana).status());
        assertEquals(404, console.post("/console/v1/organization/guardrails", dana, Map.of("name", "x")).status());
        assertEquals(404, console.post("/console/v1/organization/accounts/" + SANDBOX + ":close", dana, Map.of("reason", "no")).status());
        assertEquals(404, console.post("/console/v1/organization/accounts/" + SANDBOX + "/break-glass", dana,
                Map.of("userId", "user_dana", "reason", "me")).status());
        assertEquals(401, console.get("/console/v1/organization/accounts", null).status());
    }

    @Test
    void anAdminListsEveryAccountAndAnUnknownOneIsNotFound() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply accounts = console.get("/console/v1/organization/accounts", gonzalo);
        assertEquals(200, accounts.status(), accounts.body());
        String items = accounts.json().path("items").toString();
        assertTrue(items.contains(COMPUTE) && items.contains(DATA) && items.contains(SANDBOX), "every account, the ones gonzalo is no member of too: " + items);
        assertEquals(404, console.post("/console/v1/organization/accounts/not-an-account:close", gonzalo, Map.of("reason", "x")).status());
        assertEquals(404, console.post("/console/v1/organization/accounts/999999999999:close", gonzalo, Map.of("reason", "x")).status());
    }

    @Test
    void aGuardrailRefusesWhatARoleAllowsAndTheRefusalIsNotRequestable() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply created = console.post("/console/v1/organization/guardrails", gonzalo, Map.of("name", "no-vm-deletes-in-compute",
                "description", "the freeze", "accounts", List.of(COMPUTE),
                "statements", List.of(Map.of("sid", "NoDeletes", "effect", "deny", "actions", List.of("compute:delete"), "resources", List.of("*")))));
        assertEquals(201, created.status(), created.body());
        String guardrailId = created.json().path("guardrailId").asText();
        try {
            assertTrue(guardrailId.startsWith("gr_"), created.body());
            assertEquals(409, console.post("/console/v1/organization/guardrails", gonzalo, Map.of("name", "no-vm-deletes-in-compute", "accounts",
                    List.of(COMPUTE), "statements", List.of(Map.of("effect", "deny", "actions", List.of("x:y"), "resources", List.of("*"))))).status(),
                    "a name is unique in the organization");
            Console.Reply listed = console.get("/console/v1/organization/guardrails", gonzalo);
            assertEquals(200, listed.status(), listed.body());
            assertTrue(listed.json().path("items").toString().contains(guardrailId));
            Console.Reply applying = console.get("/console/v1/iam/guardrails", gonzalo);
            assertEquals(200, applying.status(), applying.body());
            assertTrue(applying.json().path("items").toString().contains(guardrailId), "the account's guardrails: " + applying.body());

            Console.Signed fresh = console.signIn("gonzalo");
            JsonNode delete = TypesEndpointTest.action(TypesEndpointTest.find(console.get("/console/v1/types", fresh).json(), "compute/vm"), "delete");
            assertFalse(delete.path("allowed").asBoolean(true), "an admin may not delete past the guardrail: " + delete);
            JsonNode denied = delete.path("denied");
            assertEquals("console-iam", denied.path("layer").asText(), denied.toString());
            assertEquals("guardrail", denied.path("refusedBy").asText(), denied.toString());
            assertEquals(guardrailId, denied.path("guardrailId").asText(), denied.toString());
            assertFalse(denied.path("requestable").asBoolean(true), "asking for a role does not get past a guardrail: " + denied);
            assertTrue(denied.path("suggestedRole").isMissingNode() || denied.path("suggestedRole").isNull(), denied.toString());

            Console.Reply simulated = console.post("/console/v1/iam/policy-simulations", fresh, Map.of("principal", Map.of("userId", "user_gonzalo"),
                    "checks", List.of(Map.of("action", "compute:delete", "resource", "wfrn:wf:compute:global:" + COMPUTE + ":vm/vm-1"))));
            assertEquals(200, simulated.status(), simulated.body());
            assertEquals("guardrail", simulated.json().path("results").get(0).path("scope").asText(), "the simulator's own body: " + simulated.body());

            Console.Reply stale = console.send(HttpMethod.PATCH, "/console/v1/organization/guardrails/" + guardrailId, gonzalo,
                    Map.of("description", "late"), Map.of("If-Match", "\"7\""));
            assertEquals(409, stale.status(), "If-Match goes to the identity service: " + stale.body());
            Console.Reply patched = console.send(HttpMethod.PATCH, "/console/v1/organization/guardrails/" + guardrailId, gonzalo,
                    Map.of("description", "the winter freeze"), Map.of("If-Match", "\"1\""));
            assertEquals(200, patched.status(), patched.body());
            assertEquals(2, patched.json().path("version").asInt(), patched.body());
            assertEquals("the winter freeze", console.get("/console/v1/organization/guardrails/" + guardrailId, gonzalo).json().path("description").asText());
        } finally {
            Console.Reply removed = console.send(HttpMethod.DELETE, "/console/v1/organization/guardrails/" + guardrailId, gonzalo, null, Map.of());
            assertEquals(204, removed.status(), removed.body());
        }
        assertEquals(404, console.get("/console/v1/organization/guardrails/" + guardrailId, gonzalo).status());
        Console.Signed after = console.signIn("gonzalo");
        JsonNode delete = TypesEndpointTest.action(TypesEndpointTest.find(console.get("/console/v1/types", after).json(), "compute/vm"), "delete");
        assertTrue(delete.path("allowed").asBoolean(false), "without the guardrail the admin deletes again: " + delete);
    }

    @Test
    void everyOrganizationWriteNeedsAFreshSignIn() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Session session = store.find(gonzalo.cookie()).block();
        assertNotNull(session, "the cookie names the session");
        store.save(session.reauthenticatedAt(Instant.now().minus(Duration.ofHours(1)))).block();
        Console.Reply refused = console.post("/console/v1/organization/guardrails", gonzalo, Map.of("name", "late-guardrail", "accounts", List.of("*"),
                "statements", List.of(Map.of("effect", "deny", "actions", List.of("x:y"), "resources", List.of("*")))));
        assertEquals(403, refused.status(), refused.body());
        assertEquals("step-up.required", refused.json().path("error").asText(), refused.body());
        assertEquals("guardrail:create", refused.json().path("detail").path("action").asText(), refused.body());
        assertTrue(refused.json().path("detail").path("login").asText().contains("stepUp=true"), refused.body());
        assertEquals(403, console.post("/console/v1/organization/accounts/" + SANDBOX + ":close", gonzalo, Map.of("reason", "x")).status());
        assertEquals(200, console.get("/console/v1/organization/guardrails", gonzalo).status(), "reading needs no fresh sign-in");
        assertFalse(console.get("/console/v1/organization/guardrails", gonzalo).body().contains("late-guardrail"));
    }

    @Test
    void breakGlassIsApprovedByTheAdminCallingAndClosingTheAccountEndsIt() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply given = console.post("/console/v1/organization/accounts/" + SANDBOX + "/break-glass", gonzalo,
                Map.of("userId", "user_mateo", "reason", "the sandbox's pipeline is down", "approvedBy", "user_mateo", "durationMinutes", 30,
                        "ticket", "INC-1042"));
        assertEquals(201, given.status(), given.body());
        JsonNode access = given.json();
        assertEquals("break-glass", access.path("kind").asText());
        assertEquals("active", access.path("status").asText());
        assertEquals("account-admin", access.path("role").asText());
        assertEquals("user_gonzalo", access.path("decidedBy").asText(), "approvedBy is the admin calling, whatever the body said: " + given.body());
        assertTrue(access.path("requestedBy").asText().startsWith("svc:"), "the internal principal asked: " + given.body());

        Console.Reply self = console.post("/console/v1/organization/accounts/" + SANDBOX + "/break-glass", gonzalo,
                Map.of("userId", "user_gonzalo", "reason", "myself"));
        assertEquals(400, self.status(), "nobody approves their own break-glass access: " + self.body());

        Console.Signed mateo = console.signIn("mateo");
        JsonNode sandbox = account(console.get("/console/v1/accounts", mateo).json(), SANDBOX);
        assertNotNull(sandbox, "an account mateo is no member of, open to him for a while");
        assertEquals("[\"account-admin\"]", sandbox.path("roles").toString());
        assertEquals("[]", sandbox.path("groups").toString());

        Console.Reply closed = console.post("/console/v1/organization/accounts/" + SANDBOX + ":close", gonzalo, Map.of("reason", "the sandbox is retired"));
        assertEquals(202, closed.status(), closed.body());
        assertEquals("closed", closed.json().path("status").asText());
        assertEquals(409, console.post("/console/v1/organization/accounts/" + SANDBOX + ":close", gonzalo, Map.of("reason", "again")).status());
        JsonNode listed = account(console.get("/console/v1/organization/accounts", gonzalo).json(), SANDBOX);
        assertEquals("closed", listed.path("status").asText(), "closed accounts are listed: " + listed);
        assertEquals(null, account(console.get("/console/v1/accounts", console.signIn("mateo")).json(), SANDBOX), "closing ended the access");
    }

    private static JsonNode account(JsonNode page, String accountId) {
        for (JsonNode a : page.path("items")) {
            if (accountId.equals(a.path("accountId").asText())) {
                return a;
            }
        }
        return null;
    }
}
