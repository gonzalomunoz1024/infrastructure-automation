package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * Plain English through the console, read here by the demo stand-in of the search indexer: the
 * reading is the list's own filter, with a sentence and how sure it is, and its total is what
 * that list shows; on a list it stays in the list's type; and a type the person may not list is
 * refused as its list refuses them.
 */
class InterpretEndpointTest extends DemoApp {

    private static final String INTERPRET = "/console/v1/search:interpret";
    private static final String VM = "/console/v1/r/vm";

    @Autowired
    PlatformClients clients;

    @Test
    void plainEnglishBecomesTheListsOwnFilter() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        ResourceFixtures.createVm(console, clients, gonzalo, "reading-" + (System.nanoTime() % 1_000_000));

        JsonNode running = read(gonzalo, "running vms tagged environment=dev, newest first", null);
        assertEquals("vm", running.path("type").asText());
        assertEquals("type:vm state:active tag:environment=dev", running.path("q").asText());
        assertEquals("active", running.path("filter").path("state").asText());
        assertEquals("dev", running.path("filter").path("tags").path("environment").asText());
        assertFalse(running.path("filter").has("q"), "nothing the list's chips cannot hold: " + running);
        assertEquals("-created", running.path("sort").asText());
        assertEquals("Virtual machines that are active, tagged environment=dev, newest first.", running.path("explanation").asText());
        assertEquals("high", running.path("confidence").asText());
        assertFalse(running.has("unparsed"), running.toString());
        long listed = console.get(VM + ":counts?state=active&tag.environment=dev", gonzalo).json().path("total").asLong();
        assertTrue(running.path("total").asLong() >= 1, running.toString());
        assertEquals(listed, running.path("total").asLong(), "the total is what the list shows");

        JsonNode failed = read(gonzalo, "failed vms tagged env=prod newest first", null);
        assertEquals("type:vm state:failed tag:env=prod", failed.path("q").asText());
        assertEquals("prod", failed.path("filter").path("tags").path("env").asText());
        assertEquals("Virtual machines that failed, tagged env=prod, newest first.", failed.path("explanation").asText());
        assertEquals(console.get(VM + ":counts?state=failed&tag.env=prod", gonzalo).json().path("total").asLong(), failed.path("total").asLong());
    }

    @Test
    void timesTyposAndFreeTextReadAsTheIndexerReadsThem() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String name = "spoken-" + (System.nanoTime() % 1_000_000);
        ResourceFixtures.createVm(console, clients, gonzalo, name);
        String today = LocalDate.now(ZoneOffset.UTC).toString();

        JsonNode typo = read(gonzalo, "databse created in the last 24 hours", null);
        assertEquals("database", typo.path("type").asText(), "one typo forgiven: " + typo);
        assertEquals("created:24h", typo.path("filter").path("q").asText());
        assertEquals("medium", typo.path("confidence").asText());
        assertEquals("Databases created in the last 24 hours.", typo.path("explanation").asText());
        assertEquals(console.getAt("/console/v1/r/database:counts?q={q}", gonzalo, Map.of(), "created:24h").json().path("total").asLong(),
                typo.path("total").asLong());

        JsonNode named = read(gonzalo, name + " vms created today", null);
        assertEquals("created:" + today + " " + name, named.path("filter").path("q").asText(), named.toString());
        assertEquals(1, named.path("total").asLong(), "the VM made just now: " + named);
        assertEquals("medium", named.path("confidence").asText(), "a word became free text");
        Console.Reply page = console.getAt("/console/v1/search?q={q}", gonzalo, Map.of(), named.path("q").asText());
        assertEquals(1, page.json().path("items").size(), "q is the same reading as one search: " + page.body());

        JsonNode nothing = read(gonzalo, "zzqx-nothing vms", null);
        assertEquals(List.of("zzqx-nothing"), strings(nothing.path("unparsed")), "a guess that would list nothing is left out");
        assertEquals("low", nothing.path("confidence").asText());
        assertEquals("type:vm", nothing.path("q").asText());
    }

    @Test
    void onAListTheReadingStaysInItsType() {
        Console.Signed dana = console.signIn("dana");
        JsonNode other = read(dana, "failed databases", "vm");
        assertEquals("vm", other.path("type").asText());
        assertEquals("failed", other.path("filter").path("state").asText());
        assertEquals(List.of("databases"), strings(other.path("unparsed")), "another type named is left out");
        assertEquals("low", other.path("confidence").asText());
        JsonNode sorted = read(dana, "newest first", "database");
        assertEquals("type:database", sorted.path("q").asText());
        assertEquals("-created", sorted.path("sort").asText());
        assertEquals("Databases, newest first.", sorted.path("explanation").asText());
        assertEquals(404, console.getAt(INTERPRET + "?q={q}&type=no-such-type", dana, Map.of(), "newest first").status());
    }

    @Test
    void aTypeThePersonMayNotListIsRefusedAsItsListRefusesThem() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply added = console.post("/console/v1/iam/users", gonzalo, Map.of("email", "lena@example.com", "name", "Lena Park",
                "roles", List.of("contractor")));
        assertEquals(201, added.status(), added.body());
        String userId = added.json().path("userId").asText();
        try {
            Console.Signed lena = console.signIn("lena");
            Console.Reply list = console.get(VM, lena);
            assertEquals(403, list.status(), "a custom role nobody granted anything lists nothing: " + list.body());

            Console.Reply named = console.getAt(INTERPRET + "?q={q}", lena, Map.of(), "failed vms");
            assertEquals(403, named.status(), named.body());
            assertEquals("compute:list", named.json().path("denied").path("action").asText(), named.body());
            assertEquals(list.json().path("denied").path("action").asText(), named.json().path("denied").path("action").asText(), "the list's refusal");

            Console.Reply onList = console.getAt(INTERPRET + "?q={q}&type=vm", lena, Map.of(), "newest first");
            assertEquals(403, onList.status(), onList.body());
            assertEquals("compute:list", onList.json().path("denied").path("action").asText());

            Console.Reply untyped = console.getAt(INTERPRET + "?q={q}", lena, Map.of(), "failed");
            assertEquals(200, untyped.status(), untyped.body());
            assertEquals(0, untyped.json().path("total").asLong(), "counted over the types she may list, none: " + untyped.body());
        } finally {
            console.send(HttpMethod.DELETE, "/console/v1/iam/users/" + userId, gonzalo, null, Map.of());
        }
    }

    @Test
    void whatTheTextOrTheIndexerRefusesIsA400() {
        Console.Signed mia = console.signIn("mia");
        Console.Reply missing = console.get(INTERPRET, mia);
        assertEquals(400, missing.status(), missing.body());
        assertEquals("/q", missing.json().path("problems").path(0).path("field").asText(), missing.body());
        assertEquals(400, console.getAt(INTERPRET + "?q={q}", mia, Map.of(), "x".repeat(501)).status(), "the BFF reads 500 at most");
        Console.Reply indexer = console.getAt(INTERPRET + "?q={q}", mia, Map.of(), "vms ".repeat(70));
        assertEquals(400, indexer.status(), "the indexer reads 256 at most and its refusal is passed back: " + indexer.body());
        assertEquals("/q", indexer.json().path("problems").path(0).path("field").asText(), indexer.body());
        assertEquals(401, console.get(INTERPRET + "?q=vms", null).status());
    }

    private static JsonNode read(Console.Signed as, String q, String type) {
        Console.Reply reply = type == null ? console.getAt(INTERPRET + "?q={q}", as, Map.of(), q)
                : console.getAt(INTERPRET + "?q={q}&type={type}", as, Map.of(), q, type);
        assertEquals(200, reply.status(), q + ": " + reply.body());
        return reply.json();
    }

    private static List<String> strings(JsonNode array) {
        return ResourceFixtures.stream(array).map(JsonNode::asText).toList();
    }
}
