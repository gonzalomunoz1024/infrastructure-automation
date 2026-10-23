package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * Gate 4's guardrail line (WP 4.4): a create is refused until its required tags (owner, cost
 * centre, data class, environment) are set. The tag policy tells the create form what to pre-fill
 * from the account; the preview denies while tags fall short; a batch names every row's problems;
 * and a required tag stays on the resource once it is there.
 */
class RequiredTagsEndpointTest extends DemoApp {

    private static final Set<String> REQUIRED = Set.of("owner", "cost-centre", "data-class", "environment");

    @Autowired
    PlatformClients clients;

    @Test
    void theTagPolicyPrefillsFromTheAccount() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode compute = console.get("/console/v1/tag-policy", gonzalo).json();
        assertEquals(DemoApp.COMPUTE, compute.path("accountId").asText(), compute.toString());
        Map<String, JsonNode> tags = byKey(compute.path("required"));
        assertEquals(REQUIRED, tags.keySet(), compute.toString());
        assertEquals("team-compute", tags.get("owner").path("default").asText(), "the owner is the account's owning team");
        assertEquals("account", tags.get("owner").path("defaultFrom").asText());
        assertEquals("cc-1001", tags.get("cost-centre").path("default").asText(), "the account's own cost centre");
        assertEquals("internal", tags.get("data-class").path("default").asText());
        assertEquals("organization", tags.get("data-class").path("defaultFrom").asText());
        assertEquals("[\"public\",\"internal\",\"confidential\",\"restricted\"]", tags.get("data-class").path("values").toString());
        assertEquals("input.environment", tags.get("environment").path("defaultFrom").asText(), "the form copies the create's environment");
        assertFalse(tags.get("environment").has("default"));

        JsonNode data = console.get("/console/v1/tag-policy?account=" + DemoApp.DATA, gonzalo).json();
        assertEquals("team-data", byKey(data.path("required")).get("owner").path("default").asText(), data.toString());
        assertEquals("cc-2001", byKey(data.path("required")).get("cost-centre").path("default").asText());

        Console.Signed mateo = console.signIn("mateo");
        assertEquals(404, console.get("/console/v1/tag-policy?account=" + DemoApp.DATA, mateo).status(), "not an account of mateo's");
        assertEquals(401, console.get("/console/v1/tag-policy", null).status());
    }

    @Test
    void aCreateIsRefusedUntilItsRequiredTagsAreSet() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, Object> input = Map.of("name", "tagged-01", "image", ResourceFixtures.imageId(clients), "size", "small", "environment", "dev");

        Console.Reply bare = console.send(HttpMethod.POST, VM, gonzalo, Map.of("input", input), Map.of("Idempotency-Key", "bare-" + System.nanoTime()));
        assertEquals(400, bare.status(), bare.body());
        assertEquals(Set.of("/tags/owner", "/tags/cost-centre", "/tags/data-class", "/tags/environment"), fields(bare.json(), "tags.required"), bare.body());
        assertTrue(bare.json().path("error").asText().contains("required tag"), bare.body());
        assertFalse(stream(console.get(VM + "?limit=200", gonzalo).json().path("items")).anyMatch(i -> "tagged-01".equals(i.path("name").asText())),
                "a refused create mints nothing");

        Map<String, String> wrong = new HashMap<>(ResourceFixtures.TAGS);
        wrong.put("data-class", "secret");
        wrong.put("cost-centre", "1001");
        Console.Reply invalid = console.post(VM, gonzalo, Map.of("input", input, "tags", wrong));
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals(Set.of("/tags/data-class", "/tags/cost-centre"), fields(invalid.json(), "tags.invalid"), invalid.body());

        Console.Reply preview = console.post(VM + ":preview", gonzalo, Map.of("input", input, "tags", Map.of("owner", "team-compute")));
        assertEquals(200, preview.status(), preview.body());
        assertEquals("deny", preview.json().path("policy").path("decision").asText(), preview.body());
        JsonNode rule = stream(preview.json().path("policy").path("rules")).filter(r -> "tags.required".equals(r.path("id").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no tags.required rule: " + preview.body()));
        assertEquals("deny", rule.path("result").asText());
        assertTrue(rule.path("message").asText().contains("cost-centre") && !rule.path("message").asText().contains("owner"), rule.toString());

        Map<String, String> tags = new LinkedHashMap<>(byDefaults(console.get("/console/v1/tag-policy", gonzalo).json()));
        tags.put("environment", "dev");
        tags.put("app", "web");
        Console.Reply allowedPreview = console.post(VM + ":preview", gonzalo, Map.of("input", input, "tags", tags));
        assertFalse(allowedPreview.body().contains("tags.required"), allowedPreview.body());
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, "tagged-01", tags);
        JsonNode detail = console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).json();
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            assertEquals(tag.getValue(), detail.path("tags").path(tag.getKey()).asText(), "the create's tags are on the VM: " + detail.path("tags"));
        }

        Map<String, String> withoutOwner = new LinkedHashMap<>(tags);
        withoutOwner.remove("owner");
        Console.Reply removing = console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, withoutOwner, Map.of(), wfrn);
        assertEquals(400, removing.status(), removing.body());
        assertEquals(Set.of("/tags/owner"), fields(removing.json(), "tags.required"), "a required tag stays: " + removing.body());
        Map<String, String> secret = new LinkedHashMap<>(tags);
        secret.put("data-class", "secret");
        assertEquals(400, console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, secret, Map.of(), wfrn).status());
        Map<String, String> more = new LinkedHashMap<>(tags);
        more.remove("app");
        more.put("data-class", "confidential");
        Console.Reply changed = console.sendAt(HttpMethod.PUT, VM + "/{wfrn}/tags", gonzalo, more, Map.of(), wfrn);
        assertEquals(200, changed.status(), changed.body());
        assertEquals("confidential", console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).json().path("tags").path("data-class").asText());
    }

    @Test
    void aCreateBatchNamesEachRowsMissingTags() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Map<String, Object> defaults = Map.of("image", ResourceFixtures.imageId(clients), "size", "small", "environment", "dev");
        Map<String, String> shared = Map.of("cost-centre", "cc-1001", "data-class", "internal", "environment", "dev");
        List<Map<String, Object>> rows = List.of(
                Map.of("input", Map.of("name", "tagrow-01"), "tags", Map.of("owner", "team-compute")),
                Map.of("input", Map.of("name", "tagrow-02")));

        Console.Reply preview = console.post(VM + ":batch:preview", gonzalo, Map.of("verb", "create", "items", rows, "defaults", defaults, "tags", shared));
        assertEquals(200, preview.status(), preview.body());
        assertEquals("deny", preview.json().path("policy").path("decision").asText(), preview.body());
        assertFalse(preview.json().path("items").get(0).has("problems"), "the first row names its owner: " + preview.body());
        assertEquals("/items/1/tags/owner", preview.json().path("items").get(1).path("problems").get(0).path("field").asText(), preview.body());

        Console.Reply refused = console.post(VM + ":batch", gonzalo, Map.of("verb", "create", "items", rows, "defaults", defaults, "tags", shared));
        assertEquals(400, refused.status(), refused.body());
        assertEquals(Set.of("/items/1/tags/owner"), fields(refused.json(), "tags.required"), refused.body());

        Map<String, String> all = new LinkedHashMap<>(shared);
        all.put("owner", "team-compute");
        Console.Reply created = console.send(HttpMethod.POST, VM + ":batch", gonzalo, Map.of("verb", "create", "items", rows, "defaults", defaults, "tags", all),
                Map.of("Idempotency-Key", "tag-batch-" + System.nanoTime()));
        assertEquals(202, created.status(), created.body());
        String first = created.json().path("wfrns").get(0).asText();
        assertEquals("team-compute", console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), first).json().path("tags").path("owner").asText(),
                "each minted row carries its tags");
    }

    // ---- helpers

    private static Map<String, JsonNode> byKey(JsonNode required) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        required.forEach(t -> out.put(t.path("key").asText(), t));
        return out;
    }

    private static Map<String, String> byDefaults(JsonNode policy) {
        Map<String, String> out = new LinkedHashMap<>();
        policy.path("required").forEach(t -> {
            if (t.hasNonNull("default")) {
                out.put(t.path("key").asText(), t.path("default").asText());
            }
        });
        return out;
    }

    private static Set<String> fields(JsonNode body, String code) {
        Set<String> out = new TreeSet<>();
        body.path("problems").forEach(p -> {
            if (code.equals(p.path("code").asText())) {
                out.add(p.path("field").asText());
            }
        });
        return out;
    }
}
