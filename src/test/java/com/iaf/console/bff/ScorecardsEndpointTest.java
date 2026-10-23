package com.iaf.console.bff;

import static com.iaf.console.bff.ResourceFixtures.VM;
import static com.iaf.console.bff.ResourceFixtures.stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Compliance scorecards (WP 9.6, hub PR #19) passed through from the demo drift service: the
 * account's scorecard and packs, the heat map of the person's accounts with their names, a rule's
 * failing resources, a resource's compliance, and the one-click tags fix with the drift service's
 * refusals as they were.
 */
class ScorecardsEndpointTest extends DemoApp {

    private static final String REMEDIATE = "/console/v1/scorecards/packs/baseline/rules/{rule}:remediate";

    @Test
    void theScorecardPacksAndHeatMap() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply card = console.get("/console/v1/scorecards?days=7", gonzalo);
        assertEquals(200, card.status(), card.body());
        assertEquals(COMPUTE, card.json().path("accountId").asText());
        assertEquals("baseline", card.json().path("packs").get(0).path("name").asText());
        assertTrue(card.json().path("resources").asInt() > 0, card.body());
        assertEquals(400, console.get("/console/v1/scorecards?days=0", gonzalo).status());

        Console.Reply packs = console.get("/console/v1/scorecards/packs", gonzalo);
        assertEquals(200, packs.status(), packs.body());
        assertEquals("tags", packs.json().path("items").get(0).path("rules").get(0).path("remediation").path("kind").asText(), packs.body());

        Console.Reply map = console.get("/console/v1/scorecards/heatmap", gonzalo);
        assertEquals(200, map.status(), map.body());
        assertEquals(List.of(COMPUTE, DATA), stream(map.json().path("rows")).map(r -> r.path("accountId").asText()).toList(), map.body());
        assertEquals(List.of("Compute", "Data"), stream(map.json().path("rows")).map(r -> r.path("accountName").asText()).toList(), map.body());

        Console.Signed mateo = console.signIn("mateo");
        Console.Reply his = console.get("/console/v1/scorecards/heatmap", mateo);
        assertEquals(List.of(COMPUTE), stream(his.json().path("rows")).map(r -> r.path("accountId").asText()).toList(), "only his accounts");
        assertEquals(401, console.get("/console/v1/scorecards", null).status());
    }

    @Test
    void failingResourcesAResourcesComplianceAndTheOneClickFix() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply failing = console.get("/console/v1/scorecards/packs/baseline/rules/owner-tag/failing?type=vm", gonzalo);
        assertEquals(200, failing.status(), failing.body());
        JsonNode first = stream(failing.json().path("items")).findFirst()
                .orElseThrow(() -> new AssertionError("a seeded vm has no owner tag: " + failing.body()));
        String wfrn = first.path("wfrn").asText();
        assertEquals("fail", first.path("status").asText());
        assertEquals(404, console.get("/console/v1/scorecards/packs/baseline/rules/no-such-rule/failing", gonzalo).status());

        Console.Reply compliance = console.getAt(VM + "/{wfrn}/compliance", gonzalo, Map.of(), wfrn);
        assertEquals(200, compliance.status(), compliance.body());
        assertEquals("fail", compliance.json().path("results").get(0).path("status").asText(), "failing first: " + compliance.body());

        Console.Signed mia = console.signIn("mia");
        Console.Reply viewer = console.sendAt(HttpMethod.POST, REMEDIATE, mia, Map.of("wfrn", wfrn, "input", Map.of("owner", "team-compute")), Map.of(),
                "owner-tag");
        assertEquals(403, viewer.status(), viewer.body());
        assertEquals("scorecards:remediate", viewer.json().path("denied").path("action").asText(), viewer.body());

        Console.Reply missing = console.sendAt(HttpMethod.POST, REMEDIATE, gonzalo, Map.of("wfrn", wfrn, "input", Map.of()), Map.of(), "owner-tag");
        assertEquals(400, missing.status(), "the drift service's 400 passes on: " + missing.body());

        Console.Reply fixed = console.sendAt(HttpMethod.POST, REMEDIATE, gonzalo, Map.of("wfrn", wfrn, "input", Map.of("owner", "team-scorecards"),
                "reason", "the owner is known"), Map.of("Idempotency-Key", "fix-" + System.nanoTime()), "owner-tag");
        assertEquals(200, fixed.status(), fixed.body());
        assertEquals("tags", fixed.json().path("kind").asText());
        assertEquals("team-scorecards", fixed.json().path("tags").path("owner").asText(), fixed.body());

        Console.Reply again = console.sendAt(HttpMethod.POST, REMEDIATE, gonzalo, Map.of("wfrn", wfrn, "input", Map.of("owner", "team-compute")), Map.of(),
                "owner-tag");
        assertEquals(409, again.status(), again.body());
        assertTrue(again.json().path("error").asText().startsWith("evaluation.passing"), again.body());

        Console.Reply none = console.sendAt(HttpMethod.POST, REMEDIATE, gonzalo, Map.of("wfrn", wfrn), Map.of(), "meaningful-name");
        assertEquals(409, none.status(), none.body());
        assertTrue(none.json().path("error").asText().startsWith("remediation.none"), none.body());

        assertEquals(403, console.sendAt(HttpMethod.POST, REMEDIATE, gonzalo, Map.of("wfrn", wfrn), Map.of(Console.CSRF_HEADER, ""), "owner-tag").status(),
                "the fix is a write: CSRF applies");
    }
}
