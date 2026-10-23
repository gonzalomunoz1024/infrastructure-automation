package com.iaf.console.bff.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.GroupedCounts;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.search.domain.ResourceCounts;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.scope.AccountScope;

/**
 * Counts across accounts for the console's own callers (the executive home): the accounts go to
 * the indexer 200 at a time and the answers are added up, the filters are read by name, and no
 * policy is asked.
 */
class CountsAcrossTest {

    private static final String COMPUTE = "123456789012";
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");

    private final List<List<String>> calls = new ArrayList<>();
    private final List<AccountScope> scopes = new ArrayList<>();
    private final List<CountsQuery> queries = new ArrayList<>();
    private final FakeSearchIndex index = index();
    private final Search search = new Search(null, null, null, index);
    private final Session gonzalo = session();

    /** The indexer, answering counts over the accounts named and the index's status. */
    private FakeSearchIndex index() {
        FakeSearchIndex fake = new FakeSearchIndex();
        fake.across = (accountIds, query) -> {
            calls.add(accountIds);
            scopes.add(fake.calls.get(fake.calls.size() - 1).scope());
            queries.add(query);
            return answer(accountIds.size(), query.grouped() ? 1 : 0);
        };
        fake.status = scope -> new IndexStatus(3, 1.5, scope.accountId().equals(COMPUTE) ? NOW : null, false);
        return fake;
    }

    @Test
    void theAccountsGoTwoHundredAtATimeAndTheAnswersAddUp() {
        List<String> accounts = new ArrayList<>();
        for (int i = 0; i < 450; i++) {
            accounts.add(String.format("%012d", 100000000000L + i));
        }
        accounts.add(accounts.get(0));
        JsonNode counts = search.countsAcross(gonzalo, accounts, Map.of(), "type").block();

        assertEquals(List.of(200, 200, 50), calls.stream().map(List::size).toList(), "the indexer takes 200 accounts a call, each once");
        assertEquals(accounts.get(200), calls.get(1).get(0));
        assertTrue(scopes.stream().allMatch(s -> s.accountId().equals(COMPUTE) && s.principal().equals("user_gonzalo")), "the session asks: " + scopes);
        assertEquals(450, counts.path("total").asLong(), counts.toString());
        assertEquals(450, counts.path("byState").path("active").asLong());
        assertEquals(List.of("pending", "active", "updating", "deleting", "deleted", "failed"), names(counts.path("byState")));
        assertEquals(3, counts.path("drift").asLong());
        assertFalse(counts.has("cappedAt"), "exact counts carry no cap: " + counts);
        JsonNode groups = counts.path("groups");
        assertEquals(2, groups.size(), counts.toString());
        assertEquals("vm", groups.get(0).path("key").asText(), "the largest first");
        assertEquals(447, groups.get(0).path("total").asLong());
        assertEquals("compute", groups.get(0).path("service").asText());
        assertEquals("database", groups.get(1).path("key").asText());
        assertEquals(3, groups.get(1).path("total").asLong());
        assertEquals(3, groups.get(1).path("byState").path("active").asLong());
    }

    @Test
    void theFiltersAreReadByNameAndWithoutGroupingThereAreNoGroups() {
        JsonNode counts = search.countsAcross(gonzalo, List.of(COMPUTE), Map.of("q", " created:<2026-09-23T00:00Z updated:>=2026-09-23T00:00Z ",
                "type", "vm", "state", "deleted", "drift", "true", "region", "global", "tag.env", "prod"), null).block();
        CountsQuery query = queries.get(0);
        assertEquals("created:<2026-09-23T00:00Z updated:>=2026-09-23T00:00Z", query.q());
        assertEquals("vm", query.type());
        assertEquals("deleted", query.state());
        assertEquals(Boolean.TRUE, query.drift());
        assertEquals("global", query.region());
        assertEquals(Map.of("env", "prod"), query.tags());
        assertNull(query.groupBy());
        assertFalse(counts.has("groups"), counts.toString());

        search.countsAcross(gonzalo, List.of(COMPUTE), Map.of(), null).block();
        assertNull(queries.get(1).region(), "every region when none is named");
        assertNull(queries.get(1).q());
    }

    @Test
    void noAccountCountsNothingAndAMistakeIsRefused() {
        JsonNode none = search.countsAcross(gonzalo, List.of(), Map.of(), "account").block();
        assertEquals(0, none.path("total").asLong());
        assertEquals(0, none.path("groups").size());
        assertEquals(0, none.path("byState").path("failed").asLong());
        assertTrue(calls.isEmpty(), "nothing is asked");

        assertThrows(IllegalArgumentException.class, () -> search.countsAcross(gonzalo, List.of("12345"), Map.of(), null).block());
        assertThrows(IllegalArgumentException.class, () -> search.countsAcross(gonzalo, List.of(COMPUTE), Map.of("colour", "blue"), null).block());
        assertEquals("/groupBy", assertThrows(InvalidInputException.class,
                () -> search.countsAcross(gonzalo, List.of(COMPUTE), Map.of(), "owner").block()).problems().get(0).field());
        assertEquals("/state", assertThrows(InvalidInputException.class,
                () -> search.countsAcross(gonzalo, List.of(COMPUTE), Map.of("state", "sideways"), null).block()).problems().get(0).field());
        assertEquals("/drift", assertThrows(InvalidInputException.class,
                () -> search.countsAcross(gonzalo, List.of(COMPUTE), Map.of("drift", "maybe"), null).block()).problems().get(0).field());
        assertThrows(InvalidInputException.class, () -> search.countsAcross(gonzalo, List.of(COMPUTE), Map.of("tag.Env!", "x"), null).block());
        assertTrue(calls.isEmpty());
    }

    @Test
    void aCappedChunkMakesTheSumALowerBound() {
        ObjectNode capped = answer(1000, 0);
        capped.put("cappedAt", 1000);
        JsonNode one = GroupedCounts.of(capped, false);
        assertEquals(1000, one.path("cappedAt").asLong(), "one answer keeps its cap");
        JsonNode sum = GroupedCounts.sum(List.of(capped, answer(5, 0)), false);
        assertEquals(1005, sum.path("total").asLong());
        assertEquals(1005, sum.path("cappedAt").asLong(), "the sum is at least what was counted: " + sum);
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** What the indexer answers for some accounts: one active resource each, a database in each call and the rest VMs, one drifted. */
    private static ObjectNode answer(int resources, int databases) {
        ObjectNode answer = JsonNodeFactory.instance.objectNode();
        answer.put("total", resources);
        ObjectNode byState = answer.putObject("byState");
        for (String state : ResourceCounts.STATES) {
            byState.put(state, state.equals("active") ? resources : 0);
        }
        answer.put("drift", databases > 0 ? 1 : 0);
        answer.putNull("cappedAt");
        if (databases > 0) {
            ArrayNode groups = answer.putArray("groups");
            ObjectNode vm = groups.addObject();
            vm.put("key", "vm");
            vm.put("total", resources - databases);
            vm.putObject("byState").put("active", resources - databases);
            vm.put("drift", 1);
            vm.put("service", "compute");
            ObjectNode db = groups.addObject();
            db.put("key", "database");
            db.put("total", databases);
            db.putObject("byState").put("active", databases);
            db.put("drift", 0);
            db.put("service", "data");
        }
        return answer;
    }

    @Test
    void theLastEventIsTheIndexersAndEmptyBeforeTheFirst() {
        assertEquals(NOW, search.lastEventAt(gonzalo).block());
        Session elsewhere = new Session("s_other", gonzalo.user(), List.of(new AccountMembership("210987654321", "Data", "active", "global",
                List.of("owners"), List.of("account-admin"))), "210987654321", "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)), "https://idp.test",
                "gonzalo");
        assertNull(search.lastEventAt(elsewhere).block(), "no event yet: empty");
    }

    private static Session session() {
        Principal principal = new Principal("user_gonzalo", "gonzalo@example.com", "Gonzalo Munoz", List.of());
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of("owners"), List.of("account-admin"));
        return new Session("s_gonzalo", principal, List.of(compute), COMPUTE, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)), "https://idp.test",
                "gonzalo");
    }
}
