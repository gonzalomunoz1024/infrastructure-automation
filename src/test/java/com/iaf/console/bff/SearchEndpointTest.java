package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Search through the search indexer (here the demo stand-in over the demo state service):
 * global search with highlights, type-ahead, a list's counts by state, and the list's free text,
 * each in the person's account only.
 */
class SearchEndpointTest extends DemoApp {

    private static final String VM = "/console/v1/r/vm";

    @Test
    void globalSearchFindsAResourceByNameWithTheMatchHighlighted() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode vm = firstVm(gonzalo);
        String name = vm.path("name").asText();

        Console.Reply found = console.getAt("/console/v1/search?q={q}", gonzalo, Map.of(), name);
        assertEquals(200, found.status(), found.body());
        JsonNode hit = stream(found.json().path("items")).filter(h -> vm.path("wfrn").asText().equals(h.path("wfrn").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("the VM is found by its name: " + found.body()));
        assertEquals("vm", hit.path("type").asText());
        assertEquals("compute", hit.path("service").asText());
        assertTrue(hit.path("score").asDouble() >= 10, "a name equal to the query scores highest: " + hit);
        assertTrue(hit.path("highlights").path("name").asText().contains("<em>"), "the name's match is highlighted: " + hit);
        assertTrue(found.json().has("next"), "the page has its cursor, null on the last page: " + found.body());

        Console.Reply typed = console.getAt("/console/v1/search?q={q}&type=vm&limit=1", gonzalo, Map.of(), name);
        assertEquals(200, typed.status(), typed.body());
        assertTrue(typed.json().path("items").size() <= 1);
        assertTrue(stream(typed.json().path("items")).allMatch(h -> "vm".equals(h.path("type").asText())), typed.body());
        assertEquals(404, console.getAt("/console/v1/search?q={q}&type=no-such-type", gonzalo, Map.of(), name).status());
    }

    @Test
    void searchStaysInThePersonsAccount() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String name = firstVm(gonzalo).path("name").asText();
        Console.Signed raj = console.signIn("raj");
        Console.Reply found = console.getAt("/console/v1/search?q={q}", raj, Map.of(), name);
        assertEquals(200, found.status(), found.body());
        assertTrue(stream(found.json().path("items")).noneMatch(h -> h.path("wfrn").asText().contains(":" + COMPUTE + ":")),
                "nothing of the Compute account is found from the Data account: " + found.body());
    }

    @Test
    void searchAndSuggestRefuseWhatTheIndexWouldRefuse() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply noQuery = console.get("/console/v1/search", mia);
        assertEquals(400, noQuery.status(), noQuery.body());
        assertTrue(noQuery.body().contains("/q"), noQuery.body());
        assertEquals(400, console.getAt("/console/v1/search?q={q}", mia, Map.of(), "x".repeat(257)).status());
        assertEquals(400, console.getAt("/console/v1/search?q={q}", mia, Map.of(), "state:sideways").status(), "a term the parser refuses");
        assertEquals(400, console.get("/console/v1/suggest", mia).status());
        assertEquals(400, console.get("/console/v1/suggest?q=we&limit=21", mia).status());
        assertEquals(401, console.get("/console/v1/search?q=web", null).status());
        assertEquals(401, console.get("/console/v1/suggest?q=web", null).status());
        assertEquals(401, console.get(VM + ":counts", null).status());
    }

    @Test
    void suggestOffersResourcesTypesAndTagTerms() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode vm = firstVm(gonzalo);
        String name = vm.path("name").asText();

        Console.Reply byName = console.getAt("/console/v1/suggest?q={q}", gonzalo, Map.of(), name.substring(0, Math.min(3, name.length())));
        assertEquals(200, byName.status(), byName.body());
        assertTrue(byName.json().path("items").size() <= 8, "eight at most by default");
        assertTrue(stream(byName.json().path("items")).anyMatch(s -> "resource".equals(s.path("kind").asText()) && name.equals(s.path("label").asText())),
                byName.body());

        Console.Reply byType = console.get("/console/v1/suggest?q=vm&limit=20", gonzalo);
        assertEquals(200, byType.status(), byType.body());
        assertEquals("type", byType.json().path("items").path(0).path("kind").asText(), "a type named exactly comes first: " + byType.body());
        assertEquals("vm", byType.json().path("items").path(0).path("value").asText());

        Console.Reply one = console.getAt("/console/v1/suggest?q={q}&limit=1", gonzalo, Map.of(), name);
        assertEquals(1, one.json().path("items").size(), one.body());
    }

    @Test
    void countsGiveTheTotalEveryStateAndDrift() {
        Console.Signed dana = console.signIn("dana");
        Console.Reply counts = console.get(VM + ":counts", dana);
        assertEquals(200, counts.status(), counts.body());
        JsonNode byState = counts.json().path("byState");
        List<String> states = new ArrayList<>();
        byState.fieldNames().forEachRemaining(states::add);
        assertEquals(List.of("pending", "active", "updating", "deleting", "deleted", "failed"), states, counts.body());
        long live = byState.path("pending").asLong() + byState.path("active").asLong() + byState.path("updating").asLong()
                + byState.path("deleting").asLong() + byState.path("failed").asLong();
        assertTrue(counts.json().path("total").asLong() >= 1, counts.body());
        assertEquals(live, counts.json().path("total").asLong(), "the total leaves tombstones out: " + counts.body());
        assertTrue(counts.json().has("drift") && counts.json().path("drift").asLong() <= live, counts.body());
        assertTrue(counts.json().has("cappedAt") && counts.json().path("cappedAt").isNull(), "exact counts: " + counts.body());

        Console.Reply active = console.get(VM + ":counts?state=active", dana);
        assertEquals(200, active.status(), active.body());
        assertEquals(active.json().path("byState").path("active").asLong(), active.json().path("total").asLong());
        assertEquals(byState, active.json().path("byState"), "the chips count every state whatever the state filter");

        String name = firstVm(dana).path("name").asText();
        Console.Reply named = console.getAt(VM + ":counts?q={q}", dana, Map.of(), name);
        assertTrue(named.json().path("total").asLong() >= 1, named.body());
        assertEquals(0, console.getAt(VM + ":counts?q={q}", dana, Map.of(), "no-such-resource-anywhere").json().path("total").asLong());
        assertEquals(400, console.get(VM + ":counts?state=sideways", dana).status());
        assertEquals(404, console.get("/console/v1/r/no-such-type:counts", dana).status());
    }

    @Test
    void theListsFreeTextGoesThroughTheIndexAndShowsTheStateServicesRecord() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        JsonNode vm = firstVm(gonzalo);
        String wfrn = vm.path("wfrn").asText();
        String name = vm.path("name").asText();

        Console.Reply list = console.getAt(VM + "?q={q}", gonzalo, Map.of(), name);
        assertEquals(200, list.status(), list.body());
        JsonNode row = stream(list.json().path("items")).filter(i -> wfrn.equals(i.path("wfrn").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("the VM is listed: " + list.body()));
        assertEquals(vm.path("version").asLong(), row.path("version").asLong(), "the row is the state service's record: " + row);
        assertTrue(row.path("attributes").isObject(), row.toString());

        for (String sort : List.of("name", "-name", "created", "-created", "updated", "-updated")) {
            Console.Reply sorted = console.getAt(VM + "?q={q}&sort={sort}", gonzalo, Map.of(), name, sort);
            assertEquals(200, sorted.status(), sort + ": " + sorted.body());
        }
        assertEquals(400, console.getAt(VM + "?q={q}&sort=sideways", gonzalo, Map.of(), name).status());
        Console.Reply none = console.getAt(VM + "?q={q}", gonzalo, Map.of(), "no-such-resource-anywhere");
        assertEquals(0, none.json().path("items").size(), none.body());
        Console.Reply wrongState = console.getAt(VM + "?q={q}&state=failed", gonzalo, Map.of(), name);
        assertFalse(stream(wrongState.json().path("items")).anyMatch(i -> wfrn.equals(i.path("wfrn").asText()) && !"failed".equals(vm.path("state").asText())),
                wrongState.body());
    }

    private static JsonNode firstVm(Console.Signed as) {
        Console.Reply list = console.get(VM + "?state=active&sort=name&limit=50", as);
        assertEquals(200, list.status(), list.body());
        return stream(list.json().path("items")).filter(i -> !i.path("name").asText("").isBlank()).findFirst()
                .orElseThrow(() -> new AssertionError("the demo has an active VM with a name: " + list.body()));
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }
}
