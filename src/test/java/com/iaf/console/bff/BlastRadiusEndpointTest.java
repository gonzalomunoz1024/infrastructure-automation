package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * WP 4.2 and 4.5: what a delete would break, before and when it is asked. web-01 has a volume
 * attached in the demo seed, so its blast radius names the volume, its delete preview carries the
 * {@code dependents.exist} deny, and the delete itself is refused with 409, alone or in a batch.
 */
class BlastRadiusEndpointTest extends DemoApp {

    @Autowired
    PlatformClients clients;

    @Test
    void theBlastRadiusNamesWhatDependsOnTheResource() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");

        Console.Reply radius = console.getAt(VM + "/{wfrn}/blast-radius", gonzalo, Map.of(), web01);
        assertEquals(200, radius.status(), radius.body());
        JsonNode body = radius.json();
        assertEquals(web01, body.path("resource").path("wfrn").asText());
        assertTrue(body.path("direct").asLong() >= 1, body.toString());
        assertTrue(body.path("total").asLong() >= body.path("direct").asLong());
        assertEquals(3, body.path("depth").asInt());
        assertTrue(stream(body.path("dependents")).anyMatch(d -> "volume".equals(d.path("type").asText()) && d.path("depth").asInt() == 1), body.toString());
        JsonNode mine = body.path("byAccount").get(0);
        assertEquals(COMPUTE, mine.path("accountId").asText());
        assertTrue(mine.path("current").asBoolean());
        assertTrue(stream(body.path("edges")).anyMatch(e -> web01.equals(e.path("from").asText())), "edges name the resource by wfrn: " + body);

        assertEquals(400, console.getAt(VM + "/{wfrn}/blast-radius?depth=9", gonzalo, Map.of(), web01).status());
        Console.Reply shallow = console.getAt(VM + "/{wfrn}/blast-radius?depth=1", gonzalo, Map.of(), web01);
        assertEquals(200, shallow.status(), shallow.body());
        assertTrue(stream(shallow.json().path("dependents")).allMatch(d -> d.path("depth").asInt() == 1));

        Console.Signed mia = console.signIn("mia");
        assertEquals(200, console.getAt(VM + "/{wfrn}/blast-radius", mia, Map.of(), web01).status(), "a viewer may read what depends on it");
        assertEquals(404, console.getAt(VM + "/{wfrn}/blast-radius", gonzalo, Map.of(), "wfrn:wf:compute:global:" + DATA + ":vm/other-1").status());
    }

    @Test
    void aDeleteWithLiveDependentsIsDeniedInItsPreviewAndRefused() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");

        Console.Reply preview = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete:preview", gonzalo, Map.of("input", Map.of(), "reason", "decommissioned"),
                Map.of(), web01);
        assertEquals(200, preview.status(), preview.body());
        JsonNode policy = preview.json().path("policy");
        assertEquals("deny", policy.path("decision").asText(), preview.body());
        JsonNode rule = stream(policy.path("rules")).filter(r -> "dependents.exist".equals(r.path("id").asText())).findFirst().orElseThrow();
        assertEquals("deny", rule.path("result").asText());
        assertTrue(rule.path("message").asText().contains("web-01 has"), rule.toString());
        assertTrue(preview.json().path("blastRadius").path("direct").asLong() >= 1, preview.body());
        String volume = stream(preview.json().path("blastRadius").path("dependents")).filter(d -> "volume".equals(d.path("type").asText()))
                .map(d -> d.path("wfrn").asText()).findFirst().orElseThrow();
        assertTrue(stream(preview.json().path("affectedResources")).anyMatch(a -> volume.equals(a.path("wfrn").asText())),
                "the dependents join the affected resources: " + preview.body());

        Console.Reply refused = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete", gonzalo,
                Map.of("input", Map.of(), "confirm", true, "reason", "decommissioned"), Map.of(), web01);
        assertEquals(409, refused.status(), refused.body());
        assertEquals("dependents.exist", refused.json().path("error").asText());
        assertEquals("dependents.exist", refused.json().path("problems").get(0).path("code").asText());
        assertFalse(refused.json().path("message").asText().isBlank());
        assertTrue(stream(refused.json().path("detail").path("dependents")).anyMatch(d -> volume.equals(d.path("wfrn").asText())), refused.body());
        assertEquals("active", console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), web01).json().path("state").asText(), "nothing started");
    }

    @Test
    void aBatchPreviewShowsEachRowsDependentsAndABatchDeleteIsRefused() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String web01 = ResourceFixtures.seededVm(console, gonzalo, "web-01");
        String lone = ResourceFixtures.createVm(console, clients, gonzalo, "lone-01");

        Map<String, Object> batch = Map.of("verb", "delete", "confirm", true, "items", List.of(Map.of("wfrn", web01), Map.of("wfrn", lone),
                Map.of("wfrn", "wfrn:wf:compute:global:" + COMPUTE + ":vm/never-minted")));
        Console.Reply preview = console.post(VM + ":batch:preview", gonzalo, batch);
        assertEquals(200, preview.status(), preview.body());
        JsonNode rows = preview.json().path("items");
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).path("dependents").asLong() >= 1, rows.toString());
        assertEquals("dependents.exist", rows.get(0).path("problems").get(0).path("code").asText());
        assertEquals(0, rows.get(1).path("dependents").asLong());
        assertFalse(rows.get(1).has("problems"), "a row nothing depends on runs: " + rows.get(1));
        assertEquals("not-found", rows.get(2).path("problems").get(0).path("code").asText());
        assertEquals("deny", preview.json().path("policy").path("decision").asText());
        assertTrue(preview.json().path("affected").path("total").asLong() >= 1, preview.body());

        Console.Reply refused = console.post(VM + ":batch", gonzalo, Map.of("verb", "delete", "confirm", true,
                "items", List.of(Map.of("wfrn", web01), Map.of("wfrn", lone))));
        assertEquals(409, refused.status(), refused.body());
        assertEquals("dependents.exist", refused.json().path("error").asText());
        assertEquals(web01, refused.json().path("detail").path("rows").get(0).path("wfrn").asText());

        Console.Reply alone = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete:preview", gonzalo, Map.of("input", Map.of(), "reason", "decommissioned"),
                Map.of(), lone);
        assertEquals(200, alone.status(), alone.body());
        assertEquals(0, alone.json().path("blastRadius").path("total").asLong(), alone.body());
        assertFalse(stream(alone.json().path("policy").path("rules")).anyMatch(r -> "dependents.exist".equals(r.path("id").asText())));
    }
}
