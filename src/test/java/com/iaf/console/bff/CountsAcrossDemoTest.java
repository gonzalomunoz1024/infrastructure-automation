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
import org.springframework.http.HttpMethod;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.platform.application.PlatformClients;
import com.iaf.console.bff.search.application.Search;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;

/**
 * Counts across accounts for the console's own callers, over the demo stand-in: the indexer adds
 * up the accounts the console-internal principal names, one group per account, and an account's
 * numbers are the ones its people's lists show, times included: a tombstone was last updated when
 * it was deleted.
 */
class CountsAcrossDemoTest extends DemoApp {

    private static final String VM = "/console/v1/r/vm";

    @Autowired
    Search search;

    @Autowired
    PlatformClients clients;

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

    @Test
    void aTombstoneCountsAsDeletedFromTheMomentItWasDeleted() {
        Console.Signed gonzalo = console.signIn("gonzalo");
        String name = "gone-" + (System.nanoTime() % 1_000_000);
        String wfrn = ResourceFixtures.createVm(console, clients, gonzalo, name);
        Instant between = Instant.now();
        Console.Reply deleted = console.sendAt(HttpMethod.POST, VM + "/{wfrn}:delete", gonzalo,
                Map.of("input", Map.of(), "confirm", true, "reason", "decommissioned"), Map.of(), wfrn);
        assertEquals(202, deleted.status(), deleted.body());
        String jobId = deleted.json().path("jobId").asText();
        Console.Signed dana = console.signIn("dana");
        Awaitility.await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).until(() -> approvalOf(dana, jobId) != null);
        Console.Reply decided = console.post("/console/v1/approvals/" + approvalOf(dana, jobId) + ":decide", dana,
                Map.of("decision", "approve", "comment", "go"));
        assertEquals(202, decided.status(), decided.body());
        Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250))
                .until(() -> console.getAt(VM + "/{wfrn}", gonzalo, Map.of(), wfrn).status() == 410);

        String since = name + " created:<" + between + " updated:>=" + between;
        Console.Reply gone = console.getAt(VM + ":counts?state=deleted&q={q}", gonzalo, Map.of(), since);
        assertEquals(1, gone.json().path("total").asLong(), "created before, deleted since: " + gone.body());
        Console.Reply before = console.getAt(VM + ":counts?state=deleted&q={q}", gonzalo, Map.of(), name + " created:<" + between + " updated:<" + between);
        assertEquals(0, before.json().path("total").asLong(), before.body());
        Console.Reply live = console.getAt(VM + ":counts?q={q}", gonzalo, Map.of(), since);
        assertEquals(0, live.json().path("total").asLong(), "without state=deleted a tombstone is not listed: " + live.body());
        assertEquals(1, live.json().path("byState").path("deleted").asLong(), "but it is under its chip: " + live.body());

        JsonNode across = search.countsAcross(session(), List.of(COMPUTE, DATA), Map.of("state", "deleted", "q", since), "account").block();
        assertEquals(1, across.path("total").asLong(), across.toString());
        assertEquals(COMPUTE, across.path("groups").path(0).path("key").asText(), across.toString());
    }

    private static String approvalOf(Console.Signed as, String jobId) {
        for (JsonNode approval : console.get("/console/v1/approvals?status=pending", as).json().path("items")) {
            if (jobId.equals(approval.path("jobId").asText())) {
                return approval.path("approvalId").asText(null);
            }
        }
        return null;
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
