package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/** WP 3.1: the descriptors, their ETag, and the greying of actions by the account's policies. */
class TypesEndpointTest extends DemoApp {

    @Test
    void theVmDescriptorCarriesItsConsoleBlockAndVerbs() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply reply = console.get("/console/v1/types", gonzalo);
        assertEquals(200, reply.status(), reply.body());
        assertNotNull(reply.headers().getETag(), "an ETag");
        JsonNode vm = find(reply.json(), "compute/vm");
        assertEquals("vm", vm.path("type").asText());
        assertEquals("compute", vm.path("service").asText());
        assertEquals("vms", vm.path("plural").asText());
        assertEquals("Virtual machines", vm.path("title").asText());
        assertEquals("server", vm.path("icon").asText());
        assertTrue(vm.path("list").path("columns").toString().contains("cpu"), vm.path("list").toString());
        assertEquals("-created", vm.path("list").path("defaultSort").asText());
        assertTrue(vm.path("detail").path("tabs").toString().contains("monitoring"));
        assertEquals("pending", vm.path("lifecycle").path("building").asText());
        assertEquals("deleted", vm.path("lifecycle").path("retired").asText());
        assertTrue(vm.path("observe").path("supported").asBoolean());
        assertEquals("15m", vm.path("observe").path("interval").asText());
        assertTrue(vm.path("observe").path("discover").asBoolean());
        assertTrue(vm.path("schema").path("properties").has("cpu"));
        Set<String> verbs = new HashSet<>();
        vm.path("actions").forEach(a -> verbs.add(a.path("verb").asText()));
        assertTrue(verbs.containsAll(Set.of("create", "update", "delete", "restart", "patch")), "verbs: " + verbs);
        JsonNode delete = action(vm, "delete");
        assertTrue(delete.path("destructive").asBoolean());
        assertTrue(delete.path("approval").asBoolean());
        assertTrue(delete.path("preview").asBoolean());
        assertTrue(Set.of("POST", "PATCH", "DELETE").contains(delete.path("method").asText()));
        assertEquals("delete-vm", delete.path("workflow").asText());
        assertEquals("create", vm.path("actions").get(0).path("verb").asText(), "create comes first");
        JsonNode restart = action(vm, "restart");
        assertTrue(restart.path("bulk").asBoolean(), "vm-restart takes batches");
        assertTrue(restart.path("input").path("properties").has("reason"));
        assertFalse(restart.path("input").path("properties").has("vmId"), "the subject is not asked for in the form");
    }

    @Test
    void ifNoneMatchAnswers304() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply first = console.get("/console/v1/types", gonzalo);
        String etag = first.headers().getETag();
        Console.Reply again = console.get("/console/v1/types", gonzalo, Map.of("If-None-Match", etag));
        assertEquals(304, again.status());
        Console.Reply one = console.get("/console/v1/types/compute/vm", gonzalo);
        assertEquals(200, one.status(), one.body());
        String oneTag = one.headers().getETag();
        assertEquals(304, console.get("/console/v1/types/compute/vm", gonzalo, Map.of("If-None-Match", oneTag)).status());
        Console.Reply encoded = console.get("/console/v1/types/compute%2Fvm", gonzalo);
        assertEquals(200, encoded.status(), encoded.body());
        assertEquals("compute/vm", encoded.json().path("key").asText());
        assertEquals(404, console.get("/console/v1/types/compute/toaster", gonzalo).status());
    }

    @Test
    void aViewerSeesEveryChangeDeniedWithTheReason() {
        Console.Signed mia = console.signIn("mia");
        JsonNode vm = find(console.get("/console/v1/types", mia).json(), "compute/vm");
        JsonNode create = action(vm, "create");
        assertFalse(create.path("allowed").asBoolean(true));
        JsonNode denied = create.path("denied");
        assertEquals("console-iam", denied.path("layer").asText());
        assertEquals("compute:create", denied.path("action").asText());
        assertEquals("wfrn:wf:compute:global:" + COMPUTE + ":vm/*", denied.path("resource").asText());
        assertEquals("viewer", denied.path("role").asText());
        assertEquals("operator", denied.path("suggestedRole").asText());
        assertTrue(denied.path("requestable").asBoolean());
        assertTrue(denied.path("reason").asText().contains("viewer"), denied.toString());
        assertEquals("admin", action(vm, "delete").path("denied").path("suggestedRole").asText());
    }

    @Test
    void anOperatorMayChangeButNotDelete() {
        Console.Signed dana = console.signIn("dana");
        JsonNode vm = find(console.get("/console/v1/types", dana).json(), "compute/vm");
        assertTrue(action(vm, "create").path("allowed").asBoolean());
        assertTrue(action(vm, "restart").path("allowed").asBoolean());
        assertTrue(action(vm, "update").path("allowed").asBoolean());
        assertFalse(action(vm, "delete").path("allowed").asBoolean(true));
        assertEquals("operator", action(vm, "delete").path("denied").path("role").asText());
    }

    @Test
    void anAdminMayRunEveryAction() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode types = console.get("/console/v1/types", gonzalo).json();
        for (JsonNode type : types.path("items")) {
            for (JsonNode action : type.path("actions")) {
                assertTrue(action.path("allowed").asBoolean(false), type.path("key").asText() + " " + action.path("verb").asText());
                assertTrue(action.path("denied").isMissingNode());
            }
        }
    }

    @Test
    void theEtagDiffersBetweenAViewerAndAnAdmin() {
        String admin = console.get("/console/v1/types", console.signIn("gonzalo")).headers().getETag();
        String viewer = console.get("/console/v1/types", console.signIn("mia")).headers().getETag();
        assertFalse(admin.equals(viewer), "the ETag covers the decisions");
    }

    static JsonNode find(JsonNode list, String key) {
        for (JsonNode type : list.path("items")) {
            if (key.equals(type.path("key").asText())) {
                return type;
            }
        }
        throw new AssertionError("no type " + key + " in " + list);
    }

    static JsonNode action(JsonNode type, String verb) {
        for (JsonNode action : type.path("actions")) {
            if (verb.equals(action.path("verb").asText())) {
                return action;
            }
        }
        throw new AssertionError("no action " + verb + " on " + type.path("key").asText());
    }
}
