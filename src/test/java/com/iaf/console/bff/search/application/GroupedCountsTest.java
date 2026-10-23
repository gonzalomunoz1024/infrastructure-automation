package com.iaf.console.bff.search.application;

import static com.iaf.console.bff.search.application.SearchFixtures.CATALOGUE;
import static com.iaf.console.bff.search.application.SearchFixtures.COMPUTE;
import static com.iaf.console.bff.search.application.SearchFixtures.DATA;
import static com.iaf.console.bff.search.application.SearchFixtures.access;
import static com.iaf.console.bff.search.application.SearchFixtures.json;
import static com.iaf.console.bff.search.application.SearchFixtures.platform;
import static com.iaf.console.bff.search.application.SearchFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.search.domain.CountsRequest;
import com.iaf.console.bff.search.domain.IndexStatus;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * Grouped counts through the search application: the session's account asked as the person, the
 * organization as the console-internal principal over the accounts the person may list in, each
 * decided in that account, and the index's last event added as {@code asOf} when it is known.
 */
class GroupedCountsTest {

    private static final Instant LAST = Instant.parse("2026-10-23T11:59:30Z");
    private static final String ONE_ACCOUNT = "{\"total\":4,\"byState\":{\"active\":3,\"failed\":1},\"drift\":1,\"groups\":["
            + "{\"key\":\"volume\",\"total\":1,\"byState\":{\"active\":1},\"drift\":0,\"service\":\"storage\"},"
            + "{\"key\":\"vm\",\"total\":3,\"byState\":{\"active\":2,\"failed\":1},\"drift\":1,\"service\":\"compute\"}]}";

    private final FakeSearchIndex index = new FakeSearchIndex();
    private final Search search = SearchFixtures.search(index);

    @Test
    void theAccountIsCountedAsThePersonInTheSessionsRegion() {
        index.groups = query -> json(ONE_ACCOUNT);
        index.status = scope -> new IndexStatus(12, 0.4, LAST, false);

        JsonNode counts = search.groupedCounts(session("gonzalo", COMPUTE),
                new CountsRequest("type", null, " web ", null, "active", "false", Map.of("env", "prod", "owner", ""))).block();

        FakeSearchIndex.Call asked = index.calls("countGroups").get(0);
        assertEquals(COMPUTE, asked.scope().accountId());
        assertEquals("user_gonzalo", asked.caller().userId(), "asked as the person, with their own token");
        assertEquals(new CountsQuery("web", null, "global", "active", Map.of("env", "prod", "owner", ""), false, "type"), asked.query(),
                "the filters as asked, in the session's region");
        assertTrue(index.calls("countAcross").isEmpty(), "never as the console-internal principal for one account");
        assertEquals(4, counts.path("total").asLong());
        assertEquals("vm", counts.path("groups").path(0).path("key").asText(), "largest first: " + counts);
        assertEquals("compute", counts.path("groups").path(0).path("service").asText());
        assertEquals(0, counts.path("groups").path(0).path("byState").path("pending").asLong(), "every state, as the console writes counts");
        assertEquals(LAST.toString(), counts.path("asOf").asText(), "the index's last event");
        assertEquals("user_gonzalo", index.calls("getIndexStatus").get(0).caller().userId());
        assertFalse(counts.has("cappedAt"));
    }

    @Test
    void asOfIsLeftOutWhenTheIndexCannotSay() {
        index.groups = query -> json(ONE_ACCOUNT);
        index.status = scope -> {
            throw new IllegalStateException("the index status is unavailable");
        };
        JsonNode unknown = search.groupedCounts(session("mia", COMPUTE), request("state")).block();
        assertEquals(4, unknown.path("total").asLong(), "the counts are still answered");
        assertFalse(unknown.has("asOf"), unknown.toString());

        index.status = scope -> new IndexStatus(0, 0, null, false);
        assertFalse(search.groupedCounts(session("mia", COMPUTE), request("state")).block().has("asOf"), "before the index's first event");
    }

    @Test
    void atMostAHundredGroupsLargestFirst() {
        StringBuilder groups = new StringBuilder();
        for (int i = 1; i <= 130; i++) {
            groups.append(i == 1 ? "" : ",").append("{\"key\":\"team-").append(i).append("\",\"total\":").append(i).append(",\"byState\":{},\"drift\":0}");
        }
        index.groups = query -> json("{\"total\":8515,\"byState\":{},\"drift\":0,\"groups\":[" + groups + "]}");
        index.status = scope -> new IndexStatus(8515, 0, LAST, false);
        JsonNode counts = search.groupedCounts(session("gonzalo", COMPUTE), request("tag:owner")).block();
        assertEquals(100, counts.path("groups").size());
        assertEquals("team-130", counts.path("groups").path(0).path("key").asText());
        assertEquals("team-31", counts.path("groups").path(99).path("key").asText());
        assertEquals(8515, counts.path("total").asLong(), "the total counts every group, shown or not");
        assertEquals("tag:owner", ((CountsQuery) index.calls("countGroups").get(0).query()).groupBy());
    }

    @Test
    void theOrganizationIsCountedOverTheAccountsThePersonMayListIn() {
        List<List<String>> asked = new ArrayList<>();
        index.across = (accounts, query) -> {
            asked.add(accounts);
            return json("{\"total\":" + (2 * accounts.size()) + ",\"byState\":{\"active\":" + (2 * accounts.size()) + "},\"drift\":0,\"groups\":["
                    + String.join(",", accounts.stream().map(a -> "{\"key\":\"" + a + "\",\"total\":2,\"byState\":{\"active\":2},\"drift\":0}").toList())
                    + "]}");
        };
        index.status = scope -> new IndexStatus(4, 0, LAST, false);

        JsonNode gonzalo = search.groupedCounts(session("gonzalo", COMPUTE), new CountsRequest("account", "organization", null, null, null, null, null))
                .block();
        assertEquals(List.of(COMPUTE, DATA), asked.get(0), "every account of the person's, in one call");
        assertEquals(4, gonzalo.path("total").asLong());
        assertEquals(2, gonzalo.path("groups").size());
        assertEquals(LAST.toString(), gonzalo.path("asOf").asText());
        FakeSearchIndex.Call call = index.calls("countAcross").get(0);
        assertEquals(COMPUTE, call.scope().accountId(), "logged for the session's account");
        assertEquals(new CountsQuery(null, null, "global", null, Map.of(), null, "account"), call.query());
        assertTrue(index.calls("countGroups").isEmpty(), "the person's token never names another account");

        search.groupedCounts(session("lena", COMPUTE), new CountsRequest("state", "organization", null, "vm", null, null, null)).block();
        assertEquals(List.of(COMPUTE), asked.get(1), "lena lists VMs in the compute account only");
        search.groupedCounts(session("lena", DATA), new CountsRequest("state", "organization", null, "vm", null, null, null)).block();
        assertEquals(List.of(COMPUTE), asked.get(2), "decided in each account, whichever the session is in");

        DeniedException database = assertThrows(DeniedException.class,
                () -> search.groupedCounts(session("lena", COMPUTE), new CountsRequest("state", "organization", null, "database", null, null, null)).block());
        assertEquals("data:list", database.denial().action(), "no account counts: the session's account's refusal");
        DeniedException noah = assertThrows(DeniedException.class,
                () -> search.groupedCounts(session("noah", COMPUTE), new CountsRequest("type", "organization", null, null, null, null, null)).block());
        assertEquals("compute:list", noah.denial().action());
        assertEquals(3, asked.size(), "nothing asked when no account counts");
    }

    @Test
    void aClosedAccountAndOneWhoseCatalogueLacksTheTypeAreLeftOut() {
        List<List<String>> asked = new ArrayList<>();
        index.across = (accounts, query) -> {
            asked.add(accounts);
            return json("{\"total\":1,\"byState\":{},\"drift\":0,\"groups\":[]}");
        };
        index.status = scope -> new IndexStatus(1, 0, LAST, false);
        Session gonzalo = session("gonzalo", COMPUTE);
        Session closed = gonzalo.withAccounts(List.of(gonzalo.membership(COMPUTE).orElseThrow(),
                new AccountMembership(DATA, "Data", "closed", "global", List.of(), List.of("operator"))));
        search.groupedCounts(closed, new CountsRequest("type", "organization", null, null, null, null, null)).block();
        assertEquals(List.of(COMPUTE), asked.get(0), "a closed account is not counted");

        // the data account's catalogue holds no VMs
        Search apart = new Search(catalogueWithout(DATA, "vm"), access(), platform(), index);
        apart.groupedCounts(gonzalo, new CountsRequest("type", "organization", null, "vm", null, null, null)).block();
        assertEquals(List.of(COMPUTE), asked.get(1));
        apart.groupedCounts(session("gonzalo", DATA), new CountsRequest("type", "organization", null, "vm", null, null, null)).block();
        assertEquals(List.of(COMPUTE), asked.get(2), "the other accounts' VMs, though the session's account holds none");
        assertThrows(NotFoundException.class,
                () -> search.groupedCounts(gonzalo, new CountsRequest("type", "organization", null, "toaster", null, null, null)).block(),
                "a type no account holds is a 404, as its list is");
        assertEquals(3, asked.size());
    }

    @Test
    void withNoTypeTheAccountCountsOnlyWhenThePersonMayListEveryType() {
        index.groups = query -> json(ONE_ACCOUNT);
        index.status = scope -> new IndexStatus(4, 0, LAST, false);
        DeniedException lena = assertThrows(DeniedException.class, () -> search.groupedCounts(session("lena", COMPUTE), request("type")).block());
        assertEquals("storage:list", lena.denial().action(), "the first type of the catalogue she may not list");
        assertTrue(index.calls("countGroups").isEmpty(), "refused before the indexer is asked");

        assertEquals(4, search.groupedCounts(session("lena", COMPUTE), new CountsRequest("state", null, null, "vm", null, null, null)).block()
                .path("total").asLong(), "the type she may list");
        assertEquals("vm", ((CountsQuery) index.calls("countGroups").get(0).query()).type());
        DeniedException database = assertThrows(DeniedException.class,
                () -> search.groupedCounts(session("lena", COMPUTE), new CountsRequest("state", "account", null, "database", null, null, null)).block());
        assertEquals("data:list", database.denial().action());
        assertThrows(NotFoundException.class,
                () -> search.groupedCounts(session("lena", COMPUTE), new CountsRequest("state", null, null, "toaster", null, null, null)).block());
        assertEquals(4, search.groupedCounts(session("mia", COMPUTE), request("type")).block().path("total").asLong(), "mia may list every type");
    }

    @Test
    void whatTheRequestGetsWrongIsA400() {
        Session mia = session("mia", COMPUTE);
        assertProblem("/groupBy", "required", () -> search.groupedCounts(mia, request(null)).block());
        assertProblem("/groupBy", "required", () -> search.groupedCounts(mia, request("  ")).block());
        assertProblem("/groupBy", "pattern", () -> search.groupedCounts(mia, request("owner")).block());
        assertProblem("/groupBy", "pattern", () -> search.groupedCounts(mia, request("tag:")).block());
        assertProblem("/groupBy", "pattern", () -> search.groupedCounts(mia, request("tag:" + "x".repeat(129))).block());
        assertProblem("/scope", "enum", () -> search.groupedCounts(mia, new CountsRequest("type", "galaxy", null, null, null, null, null)).block());
        assertProblem("/state", "enum", () -> search.groupedCounts(mia, new CountsRequest("type", null, null, null, "sideways", null, null)).block());
        assertProblem("/drift", "type", () -> search.groupedCounts(mia, new CountsRequest("type", null, null, null, null, "maybe", null)).block());
        assertProblem("/tag.Bad_Name", "tag.name",
                () -> search.groupedCounts(mia, new CountsRequest("type", null, null, null, null, null, Map.of("Bad_Name", "x"))).block());
        assertProblem("/q", "length", () -> search.groupedCounts(mia, new CountsRequest("type", null, "q".repeat(501), null, null, null, null)).block());
        assertTrue(index.calls.isEmpty(), "nothing asked of the indexer: " + index.calls);
    }

    private static CountsRequest request(String groupBy) {
        return new CountsRequest(groupBy, null, null, null, null, null, null);
    }

    private static void assertProblem(String field, String code, Runnable call) {
        InvalidInputException e = assertThrows(InvalidInputException.class, call::run);
        assertEquals(field, e.problems().get(0).field(), e.getMessage());
        assertEquals(code, e.problems().get(0).code(), e.getMessage());
    }

    /** The catalogue, except that one account's holds no type of that name. */
    private static Types catalogueWithout(String accountId, String type) {
        return new Types(null, null, null, null, null) {
            @Override
            public Mono<List<ConsoleType>> list(Session session) {
                return Mono.just(CATALOGUE.stream().filter(t -> !session.accountId().equals(accountId) || !t.type().equals(type)).toList());
            }

            @Override
            public Mono<ConsoleType> byType(Session session, String name) {
                return list(session).flatMap(all -> Mono.justOrEmpty(all.stream().filter(t -> t.type().equals(name)).findFirst()))
                        .switchIfEmpty(Mono.error(NotFoundException.of("type", name)));
            }
        };
    }
}
