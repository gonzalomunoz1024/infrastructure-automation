package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

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
}
