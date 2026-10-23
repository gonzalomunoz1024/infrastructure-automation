package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * WP 4.6, Launch more like this: a resource's create form, filled from what it was created with
 * and what it is now, for this account or another of the person's, with the accounts the person
 * may create it in; the draft creates as it stands.
 */
class CloneEndpointTest extends DemoApp {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = Long.toString(System.nanoTime() % 1_000_000);

    @Autowired
    PlatformClients clients;

    @Test
    void aVmIsLaunchedAgainFromItsDraft() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, String> tags = new HashMap<>(ResourceFixtures.TAGS);
        tags.put("app", "web");
        String source = ResourceFixtures.createVm(console, clients, gonzalo, "cl-" + RUN, tags);

        Console.Reply reply = console.getAt(VM + "/{wfrn}/clone", gonzalo, Map.of(), source);
        assertEquals(200, reply.status(), reply.body());
        JsonNode draft = reply.json();
        assertEquals("vm", draft.path("type").asText());
        assertEquals("create-vm", draft.path("workflow").asText());
        assertEquals(source, draft.path("source").path("wfrn").asText());
        assertEquals(DemoApp.COMPUTE, draft.path("accountId").asText());
        assertEquals("cl-" + RUN + "-copy", draft.path("input").path("name").asText(), "a new name: " + draft.path("input"));
        assertEquals(ResourceFixtures.imageId(clients), draft.path("input").path("image").asText());
        assertEquals("small", draft.path("input").path("size").asText(), "what it was created with: " + draft.path("input"));
        assertEquals("dev", draft.path("input").path("environment").asText());
        assertEquals(tags, JSON.convertValue(draft.path("tags"), new TypeReference<Map<String, String>>() { }), "the source's tags");
        assertEquals(DemoApp.COMPUTE, draft.path("tagPolicy").path("accountId").asText());
        JsonNode targets = draft.path("targets");
        assertEquals(2, targets.size(), "gonzalo's two accounts: " + targets);
        assertTrue(stream(targets).allMatch(t -> t.path("allowed").asBoolean()), targets.toString());
        assertTrue(stream(targets).anyMatch(t -> DemoApp.COMPUTE.equals(t.path("accountId").asText()) && t.path("current").asBoolean()));
        assertFalse(draft.path("notes").toString().contains("reference"), "no note about references within the account: " + draft.path("notes"));

        Map<String, Object> input = JSON.convertValue(draft.path("input"), new TypeReference<Map<String, Object>>() { });
        Console.Reply launched = console.send(HttpMethod.POST, VM, gonzalo, Map.of("input", input, "tags", draft.path("tags")),
                Map.of("Idempotency-Key", "clone-" + RUN));
        assertEquals(202, launched.status(), "the draft creates as it stands: " + launched.body());
        ResourceFixtures.awaitState(console, gonzalo, launched.json().path("wfrn").asText(), "active");

        JsonNode elsewhere = console.getAt(VM + "/{wfrn}/clone?account=" + DemoApp.DATA, gonzalo, Map.of(), source).json();
        assertEquals(DemoApp.DATA, elsewhere.path("accountId").asText(), elsewhere.toString());
        assertEquals("team-data", elsewhere.path("tags").path("owner").asText(), "the target account's owner: " + elsewhere.path("tags"));
        assertEquals("cc-2001", elsewhere.path("tags").path("cost-centre").asText(), "and its cost centre");
        assertEquals("web", elsewhere.path("tags").path("app").asText(), "the other tags stay");
        assertTrue(stream(elsewhere.path("notes")).anyMatch(n -> "image".equals(n.path("field").asText()) && "reference".equals(n.path("code").asText())),
                "the image is the source account's: " + elsewhere.path("notes"));
        assertEquals(DemoApp.DATA, elsewhere.path("tagPolicy").path("accountId").asText());
    }

    @Test
    void theTargetsSayWhereThePersonMayCreate() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String source = ResourceFixtures.createVm(console, clients, gonzalo, "cl-t-" + RUN);

        Console.Signed mateo = console.signIn("mateo");
        assertEquals(404, console.getAt(VM + "/{wfrn}/clone?account=" + DemoApp.DATA, mateo, Map.of(), source).status(), "not an account of mateo's");
        JsonNode operator = console.getAt(VM + "/{wfrn}/clone", mateo, Map.of(), source).json();
        assertEquals(1, operator.path("targets").size(), operator.toString());
        assertTrue(operator.path("targets").get(0).path("allowed").asBoolean(), "an operator creates VMs");

        Console.Signed mia = console.signIn("mia");
        JsonNode viewer = console.getAt(VM + "/{wfrn}/clone", mia, Map.of(), source).json();
        assertFalse(viewer.path("targets").get(0).path("allowed").asBoolean(), viewer.toString());
        assertEquals("compute:create", viewer.path("targets").get(0).path("denied").path("action").asText(), viewer.toString());

        assertEquals(404, console.getAt(VM + "/{wfrn}/clone", gonzalo, Map.of(), "wfrn:wf:compute:global:" + DemoApp.COMPUTE + ":vm/never-minted").status());
    }
}
