package com.iaf.console.bff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.search.application.Search;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

/**
 * Counts across accounts for the console's own callers, over the demo stand-in: the indexer adds
 * up the accounts the console-internal principal names, one group per account, and an account's
 * numbers are the ones its people's lists show.
 */
class CountsAcrossDemoTest extends DemoApp {

    @Autowired
    Search search;

    @Test
    void theAccountsNamedAreAddedUpAndEachIsAGroup() {
        Session gonzalo = session();
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            JsonNode compute = search.countsAcross(gonzalo, List.of(COMPUTE), Map.of(), null).block();
            JsonNode data = search.countsAcross(gonzalo, List.of(DATA), Map.of(), null).block();
            JsonNode both = search.countsAcross(gonzalo, List.of(COMPUTE, DATA), Map.of(), "account").block();
            assertFalse(compute.has("groups"), "no groups without a grouping: " + compute);
            assertEquals(compute.path("total").asLong() + data.path("total").asLong(), both.path("total").asLong(), both.toString());
            Map<String, Long> groups = new HashMap<>();
            both.path("groups").forEach(g -> groups.put(g.path("key").asText(), g.path("total").asLong()));
            assertEquals(Map.of(COMPUTE, compute.path("total").asLong(), DATA, data.path("total").asLong()), groups, both.toString());
        });
    }

    @Test
    void anAccountsNumbersAreTheOnesItsListsShow() {
        Session session = session();
        Console.Signed gonzalo = console.signIn("gonzalo");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            JsonNode list = console.get("/console/v1/r/vm:counts", gonzalo).json();
            JsonNode across = search.countsAcross(session, List.of(COMPUTE), Map.of("type", "vm", "region", "global"), "state").block();
            assertEquals(list.path("total").asLong(), across.path("total").asLong(), across.toString());
            assertEquals(list.path("byState").toString(), across.path("byState").toString());
            assertEquals(list.path("drift").asLong(), across.path("drift").asLong());
            long grouped = 0;
            for (JsonNode group : across.path("groups")) {
                grouped += group.path("total").asLong();
            }
            assertEquals(across.path("total").asLong(), grouped, "every listed resource is in its state's group: " + across);
        });
        JsonNode deleted = search.countsAcross(session, List.of(COMPUTE, DATA), Map.of("state", "deleted"), null).block();
        assertEquals(deleted.path("byState").path("deleted").asLong(), deleted.path("total").asLong(), "with state=deleted the tombstones are listed");
    }

    /** Gonzalo's session in the compute account, as the executive home holds one. */
    private static Session session() {
        Instant now = Instant.now();
        Principal principal = new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of());
        List<AccountMembership> accounts = List.of(
                new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin")),
                new AccountMembership(DATA, "Data", "active", "global", List.of("owners"), List.of("account-admin")));
        return new Session("s_counts_across", principal, accounts, COMPUTE, "global", now, now, now, now.plus(Duration.ofHours(1)), BASE + "/demo-idp",
                "gonzalo");
    }
}
