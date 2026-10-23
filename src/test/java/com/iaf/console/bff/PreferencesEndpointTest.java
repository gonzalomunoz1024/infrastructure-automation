package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.Test;

/** The person's preferences, versioned. */
class PreferencesEndpointTest extends DemoApp {

    @Test
    void preferencesAreReplacedWithTheirVersion() {
        Console.Signed ana = console.signIn("ana");
        Console.Reply defaults = console.get("/console/v1/preferences", ana);
        assertEquals(200, defaults.status(), defaults.body());
        assertEquals("system", defaults.json().path("theme").asText());
        assertEquals(0, defaults.json().path("version").asInt());
        Console.Reply saved = console.put("/console/v1/preferences", ana,
                Map.of("theme", "dark", "density", "compact", "pinnedTypes", List.of("data/database"), "columns", Map.of("data/database", List.of("name", "tier")), "version", 0));
        assertEquals(200, saved.status(), saved.body());
        assertEquals(1, saved.json().path("version").asInt());
        assertEquals("dark", console.get("/console/v1/preferences", ana).json().path("theme").asText());
        Console.Reply stale = console.put("/console/v1/preferences", ana, Map.of("theme", "light", "version", 0));
        assertEquals(409, stale.status(), stale.body());
        Console.Reply invalid = console.put("/console/v1/preferences", ana, Map.of("theme", "neon", "version", 1));
        assertEquals(400, invalid.status(), invalid.body());
        Console.Reply unversioned = console.put("/console/v1/preferences", ana, Map.of("theme", "light"));
        assertEquals(200, unversioned.status(), unversioned.body());
        assertEquals(2, unversioned.json().path("version").asInt());
        assertEquals(401, console.get("/console/v1/preferences", null).status());
    }

    @Test
    void savedViewsAreKeptPerTypeAsTheConsoleSentThem() {
        Console.Signed raj = console.signIn("raj");
        Map<String, Object> freeText = new HashMap<>();
        freeText.put("property", null);
        freeText.put("operator", ":");
        freeText.put("value", "orders");
        Map<String, Object> view = Map.of("id", "v_01jbx", "name", "Prod orders", "filters", List.of(Map.of("property", "tag.env", "operator", "=", "value", "prod"), freeText),
                "sort", "-created", "pageSize", 100, "createdAt", "2026-10-23T12:00:00Z");
        Console.Reply saved = console.put("/console/v1/preferences", raj, Map.of("savedViews", Map.of("data/database", List.of(view)), "version", 0));
        assertEquals(200, saved.status(), saved.body());
        JsonNode kept = console.get("/console/v1/preferences", raj).json().path("savedViews").path("data/database").path(0);
        assertEquals("Prod orders", kept.path("name").asText(), kept.toString());
        assertTrue(kept.path("filters").path(1).has("property") && kept.path("filters").path(1).path("property").isNull(), "free text keeps its null property: " + kept);
        assertEquals("-created", kept.path("sort").asText());
        assertEquals(100, kept.path("pageSize").asInt());
        List<Object> twice = new ArrayList<>(List.of(view, view));
        Console.Reply duplicate = console.put("/console/v1/preferences", raj, Map.of("savedViews", Map.of("data/database", twice), "version", 1));
        assertEquals(400, duplicate.status(), duplicate.body());
        Console.Reply stale = console.put("/console/v1/preferences", raj, Map.of("savedViews", Map.of(), "version", 0));
        assertEquals(409, stale.status(), stale.body());
    }
}
