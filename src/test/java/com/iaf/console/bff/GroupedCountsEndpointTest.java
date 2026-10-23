package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;

/**
 * Grouped counts through the console, over the demo stand-in of the search indexer: a grouping's
 * numbers are the ones the lists show, in the session's account or across every account the
 * person may list in, as of the index's last event; and what a request gets wrong, or a person
 * may not list, is refused as the lists refuse it.
 */
class GroupedCountsEndpointTest extends DemoApp {

    private static final String COUNTS = "/console/v1/search:counts";
    private static final String VM = "/console/v1/r/vm";

    @Autowired
    PlatformClients clients;

    @Test
    void aGroupingsNumbersAreTheOnesTheListsShow() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Instant before = Instant.now();
        String name = "grouped-" + (System.nanoTime() % 1_000_000);
        ResourceFixtures.createVm(console, clients, gonzalo, name);

        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            JsonNode byState = counts(gonzalo, "?groupBy=state");
            assertEquals(byState.path("total").asLong(), sum(byState), "every resource is in its state's group: " + byState);
            byState.path("groups").forEach(g -> assertEquals(g.path("total").asLong(), byState.path("byState").path(g.path("key").asText()).asLong()));
            assertLargestFirst(byState);

            JsonNode byType = counts(gonzalo, "?groupBy=type");
            JsonNode vms = group(byType, "vm");
            assertEquals(console.get(VM + ":counts", gonzalo).json().path("total").asLong(), vms.path("total").asLong(), byType.toString());
            assertEquals("compute", vms.path("service").asText(), "a type's group names its service");
            assertEquals(byType.path("total").asLong(), sum(byType));

            JsonNode byTag = counts(gonzalo, "?groupBy=tag:environment&type=vm");
            assertEquals(console.get(VM + ":counts?tag.environment=dev", gonzalo).json().path("total").asLong(), group(byTag, "dev").path("total").asLong(),
                    byTag.toString());
        });

        JsonNode named = counts(gonzalo, "?groupBy=state&q=" + name);
        assertEquals(1, named.path("total").asLong(), "the VM made just now: " + named);
        assertEquals("active", named.path("groups").path(0).path("key").asText());
        JsonNode tagged = console.getAt(COUNTS + "?groupBy=type&tag.environment={v}&state=active&drift=false", gonzalo, Map.of(), "dev").json();
        assertTrue(group(tagged, "vm").path("total").asLong() >= 1, tagged.toString());

        Instant asOf = Instant.parse(named.path("asOf").asText());
        assertFalse(asOf.isBefore(before), "as of the index's last event, after the VM was made: " + asOf);
        assertFalse(asOf.isAfter(Instant.now()), asOf.toString());
    }

    @Test
    void theOrganizationIsEveryAccountThePersonMayListIn() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Signed raj = console.signIn("raj");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            JsonNode both = counts(gonzalo, "?groupBy=account&scope=organization");
            Map<String, Long> byAccount = new LinkedHashMap<>();
            both.path("groups").forEach(g -> byAccount.put(g.path("key").asText(), g.path("total").asLong()));
            assertEquals(Map.of(COMPUTE, counts(gonzalo, "?groupBy=state").path("total").asLong(), DATA, counts(raj, "?groupBy=state").path("total").asLong()),
                    byAccount, "each account as its own people count it: " + both);
            assertEquals(both.path("total").asLong(), sum(both));
            assertLargestFirst(both);
        });
        assertTrue(counts(gonzalo, "?groupBy=account&scope=organization").has("asOf"));

        Console.Signed dana = console.signIn("dana");
        JsonNode hers = counts(dana, "?groupBy=account&scope=organization&type=vm");
        assertEquals(List.of(COMPUTE), ResourceFixtures.stream(hers.path("groups")).map(g -> g.path("key").asText()).toList(),
                "dana is a member of the compute account only: " + hers);
        assertEquals(1, counts(dana, "?groupBy=region").path("groups").size(), "an account's counts are in the session's region");
    }

    @Test
    void whatTheRequestGetsWrongIsA400() {
        Console.Signed mia = console.signIn("mia");
        assertProblem(mia, "", "/groupBy");
        assertProblem(mia, "?groupBy=owner", "/groupBy");
        assertProblem(mia, "?groupBy=tag:", "/groupBy");
        assertProblem(mia, "?groupBy=type&scope=galaxy", "/scope");
        assertProblem(mia, "?groupBy=type&state=sideways", "/state");
        assertProblem(mia, "?groupBy=type&drift=maybe", "/drift");
        assertProblem(mia, "?groupBy=type&tag.Bad_Name=x", "/tag.Bad_Name");
        Console.Reply longer = console.getAt(COUNTS + "?groupBy=type&q={q}", mia, Map.of(), "x".repeat(501));
        assertEquals(400, longer.status(), "the BFF reads 500 at most");
        Console.Reply indexer = console.get(COUNTS + "?groupBy=tag:Cost.Centre", mia);
        assertEquals(400, indexer.status(), "a tag name the indexer does not take: its refusal is passed back: " + indexer.body());
        assertEquals("/groupBy", indexer.json().path("problems").path(0).path("field").asText(), indexer.body());
        assertEquals(404, console.get(COUNTS + "?groupBy=state&type=no-such-type", mia).status());
        assertEquals(401, console.get(COUNTS + "?groupBy=type", null).status());
    }

    @Test
    void aPersonWhoMayNotListIsRefusedAsTheListsRefuseThem() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        Console.Reply added = console.post("/console/v1/iam/users", gonzalo, Map.of("email", "ines@example.com", "name", "Ines Duarte",
                "roles", List.of("contractor")));
        assertEquals(201, added.status(), added.body());
        String userId = added.json().path("userId").asText();
        try {
            Console.Signed ines = console.signIn("ines");
            Console.Reply list = console.get(VM, ines);
            assertEquals(403, list.status(), "a custom role nobody granted anything lists nothing: " + list.body());

            Console.Reply typed = console.get(COUNTS + "?groupBy=state&type=vm", ines);
            assertEquals(403, typed.status(), typed.body());
            assertEquals(list.json().path("denied").path("action").asText(), typed.json().path("denied").path("action").asText(), "the list's refusal");

            Console.Reply account = console.get(COUNTS + "?groupBy=type", ines);
            assertEquals(403, account.status(), account.body());
            assertTrue(account.json().path("denied").path("action").asText().endsWith(":list"), account.body());

            Console.Reply organization = console.get(COUNTS + "?groupBy=account&scope=organization", ines);
            assertEquals(403, organization.status(), "no account she may list in: " + organization.body());
        } finally {
            console.send(HttpMethod.DELETE, "/console/v1/iam/users/" + userId, gonzalo, null, Map.of());
        }
    }

    private static JsonNode counts(Console.Signed as, String query) {
        Console.Reply reply = console.get(COUNTS + query, as);
        assertEquals(200, reply.status(), query + ": " + reply.body());
        JsonNode counts = reply.json();
        assertTrue(counts.path("groups").size() <= 100, "at most 100 groups");
        return counts;
    }

    private static JsonNode group(JsonNode counts, String key) {
        return ResourceFixtures.stream(counts.path("groups")).filter(g -> key.equals(g.path("key").asText())).findFirst()
                .orElseThrow(() -> new AssertionError("no group " + key + ": " + counts));
    }

    private static long sum(JsonNode counts) {
        return ResourceFixtures.stream(counts.path("groups")).mapToLong(g -> g.path("total").asLong()).sum();
    }

    private static void assertLargestFirst(JsonNode counts) {
        long previous = Long.MAX_VALUE;
        for (JsonNode group : counts.path("groups")) {
            assertTrue(group.path("total").asLong() <= previous, "largest first: " + counts);
            previous = group.path("total").asLong();
        }
    }

    private static void assertProblem(Console.Signed as, String query, String field) {
        Console.Reply reply = console.get(COUNTS + query, as);
        assertEquals(400, reply.status(), query + ": " + reply.body());
        assertEquals(field, reply.json().path("problems").path(0).path("field").asText(), reply.body());
    }
}
