package com.iaf.console.bff.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iaf.console.bff.access.application.Access;
import com.iaf.console.bff.access.application.AccessProperties;
import com.iaf.console.bff.access.domain.Decision;
import com.iaf.console.bff.access.domain.Decisions;
import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.access.domain.Denial;
import com.iaf.console.bff.access.ports.outbound.AuthorizationPort;
import com.iaf.console.bff.platform.application.PlatformProperties;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.bff.session.domain.AccountMembership;
import com.iaf.console.bff.session.domain.Principal;
import com.iaf.console.bff.session.domain.Session;
import com.iaf.console.bff.types.application.Types;
import com.iaf.console.bff.types.domain.ConsoleType;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;

import reactor.core.publisher.Mono;

/**
 * Plain English through the search application: the indexer's reading passed on as it is, the
 * list's type checked before the indexer is asked, a reading of a type the person may not list
 * refused as that list is, and a reading of no type counted over the types the person may list.
 */
class InterpretTest {

    static final String COMPUTE = "123456789012";
    static final String DATA = "210987654321";
    static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<ConsoleType> CATALOGUE = List.of(type("compute", "vm"), type("storage", "volume"), type("data", "database"));
    /** What each person may do, in every account: gonzalo anything, mia list only, lena list VMs only, noah nothing. */
    static final Map<String, Predicate<String>> POLICIES = Map.of(
            "user_gonzalo", action -> true,
            "user_mia", action -> action.endsWith(":list"),
            "user_lena", action -> action.equals("compute:list"),
            "user_noah", action -> false);

    private final FakeSearchIndex index = new FakeSearchIndex();
    private final Search search = new Search(catalogue(), access(), platform(), index);

    @Test
    void theReadingIsPassedOnAsTheIndexerAnswered() throws Exception {
        JsonNode reading = JSON.readTree("{\"q\":\"type:vm state:failed\",\"type\":\"vm\",\"filter\":{\"state\":\"failed\"},"
                + "\"explanation\":\"Virtual machines that failed.\",\"confidence\":\"high\",\"total\":4,\"extra\":{\"kept\":true}}");
        index.reading = (q, type) -> reading;
        assertSame(reading, search.interpret(session("gonzalo", COMPUTE), "  failed vms ", null).block());
        FakeSearchIndex.Call call = index.calls("interpret").get(0);
        assertEquals("failed vms", call.query(), "the text, trimmed, and no list type");
        assertEquals(COMPUTE, call.scope().accountId());
        assertEquals("user_gonzalo", call.caller().userId(), "asked as the person");
    }

    @Test
    void theListsTypeIsCheckedBeforeTheIndexerIsAsked() throws Exception {
        index.reading = (q, type) -> reading("{\"q\":\"type:" + type + "\",\"type\":\"" + type + "\",\"filter\":{},\"explanation\":\"x\","
                + "\"confidence\":\"high\",\"total\":2}");
        DeniedException refused = assertThrows(DeniedException.class, () -> search.interpret(session("lena", COMPUTE), "newest first", "database").block());
        assertEquals("data:list", refused.denial().action());
        assertTrue(index.calls("interpret").isEmpty(), "refused before the indexer spends the query budget");
        assertThrows(NotFoundException.class, () -> search.interpret(session("lena", COMPUTE), "newest first", "toaster").block());

        assertEquals(2, search.interpret(session("lena", COMPUTE), "newest first", "vm").block().path("total").asInt());
        assertEquals("newest first @vm", index.calls("interpret").get(0).query());
    }

    @Test
    void aReadingOfATypeThePersonMayNotListIsRefusedAsThatListIs() {
        index.reading = (q, type) -> reading("{\"q\":\"type:database\",\"type\":\"database\",\"filter\":{},\"explanation\":\"Databases.\","
                + "\"confidence\":\"high\",\"total\":5}");
        DeniedException refused = assertThrows(DeniedException.class, () -> search.interpret(session("lena", COMPUTE), "databases", null).block());
        assertEquals("data:list", refused.denial().action());
        assertEquals(5, search.interpret(session("mia", COMPUTE), "databases", null).block().path("total").asInt());
    }

    @Test
    void aReadingOfNoTypeIsCountedOverTheTypesThePersonMayList() {
        index.reading = (q, type) -> reading("{\"q\":\"state:failed tag:env web\",\"filter\":{\"q\":\"web\",\"state\":\"failed\",\"drift\":false,"
                + "\"region\":\"global\",\"tags\":{\"env\":\"\"}},\"explanation\":\"Resources that failed.\",\"confidence\":\"medium\",\"total\":9}");
        index.groups = query -> reading("{\"total\":9,\"byState\":{},\"drift\":0,\"cappedAt\":null,\"groups\":[{\"key\":\"database\",\"total\":5},"
                + "{\"key\":\"vm\",\"total\":3},{\"key\":\"volume\",\"total\":1}]}");

        JsonNode lena = search.interpret(session("lena", COMPUTE), "failed web tagged env", null).block();
        assertEquals(3, lena.path("total").asInt(), "only the VMs lena may list: " + lena);
        assertEquals("Resources that failed.", lena.path("explanation").asText(), "the rest as the indexer answered");
        CountsQuery recount = (CountsQuery) index.calls("countGroups").get(0).query();
        assertEquals(new CountsQuery("web", null, "global", "failed", Map.of("env", ""), false, "type"), recount, "the reading's own filter, by type");
        assertEquals("user_lena", index.calls("countGroups").get(0).caller().userId());

        assertEquals(9, search.interpret(session("mia", COMPUTE), "failed web tagged env", null).block().path("total").asInt(),
                "a person who may list every type sees the indexer's total");
        assertEquals(0, search.interpret(session("noah", COMPUTE), "failed web tagged env", null).block().path("total").asInt());
        assertEquals(1, index.calls("countGroups").size(), "counted again only when some types but not all are listable");
    }

    @Test
    void theTextIsRequiredAndAtMostFiveHundredCharacters() {
        index.reading = (q, type) -> reading("{\"q\":\"\",\"filter\":{},\"explanation\":\"Resources.\",\"confidence\":\"medium\",\"total\":0}");
        InvalidInputException blank = assertThrows(InvalidInputException.class, () -> search.interpret(session("mia", COMPUTE), "  ", null).block());
        assertEquals("/q", blank.problems().get(0).field());
        InvalidInputException longer = assertThrows(InvalidInputException.class,
                () -> search.interpret(session("mia", COMPUTE), "x".repeat(501), null).block());
        assertEquals("length", longer.problems().get(0).code());
        assertEquals(0, search.interpret(session("mia", COMPUTE), "x".repeat(500), null).block().path("total").asInt());
    }

    static JsonNode reading(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ConsoleType type(String service, String name) {
        return new ConsoleType(service + "/" + name, name, service, name + "s", name, "box", null, null, null, null, null, null, null, null, null);
    }

    /** The person in the account, a member of both accounts. */
    static Session session(String user, String accountId) {
        Principal principal = new Principal("user_" + user, user + "@example.com", user, List.of());
        AccountMembership compute = new AccountMembership(COMPUTE, "Compute", "active", "global", List.of(), List.of("operator"));
        AccountMembership data = new AccountMembership(DATA, "Data", "active", "global", List.of(), List.of("operator"));
        return new Session("s_" + user, principal, List.of(compute, data), accountId, "global", NOW, NOW, NOW, NOW.plus(Duration.ofHours(12)),
                "https://idp.test", user);
    }

    static Types catalogue() {
        return new Types(null, null, null, null, null) {
            @Override
            public Mono<List<ConsoleType>> list(Session session) {
                return Mono.just(CATALOGUE);
            }

            @Override
            public Mono<ConsoleType> byType(Session session, String type) {
                return Mono.justOrEmpty(CATALOGUE.stream().filter(t -> t.type().equals(type)).findFirst())
                        .switchIfEmpty(Mono.error(NotFoundException.of("type", type)));
            }
        };
    }

    /** Policies per person; in the data account lena may list nothing. */
    static Access access() {
        AuthorizationPort port = (scope, principal, groups, checks) -> Mono.just(new Decisions(checks.stream()
                .map(c -> allowed(principal.userId(), scope.accountId(), c.action()) ? Decision.allow()
                        : Decision.deny(Denial.byPolicy(c.action(), c.resource(), "No statement allows " + c.action(), null, null, null)))
                .toList(), 1));
        return new Access(port, new AccessProperties(Duration.ofSeconds(60), 1000));
    }

    private static boolean allowed(String userId, String accountId, String action) {
        if (userId.equals("user_lena") && accountId.equals(DATA)) {
            return false;
        }
        return POLICIES.getOrDefault(userId, a -> false).test(action);
    }

    static PlatformProperties platform() {
        return new PlatformProperties("http://localhost:18090", "", "wf", Duration.ofSeconds(30), Duration.ofMinutes(10), Map.of(),
                new PlatformProperties.Fake(false, 0, Duration.ofMillis(1), 1L));
    }
}
