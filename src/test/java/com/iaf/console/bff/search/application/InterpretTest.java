package com.iaf.console.bff.search.application;

import static com.iaf.console.bff.search.application.SearchFixtures.COMPUTE;
import static com.iaf.console.bff.search.application.SearchFixtures.JSON;
import static com.iaf.console.bff.search.application.SearchFixtures.json;
import static com.iaf.console.bff.search.application.SearchFixtures.session;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import com.iaf.console.bff.access.domain.DeniedException;
import com.iaf.console.bff.search.domain.CountsQuery;
import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.NotFoundException;

/**
 * Plain English through the search application: the indexer's reading passed on as it is, the
 * list's type checked before the indexer is asked, a reading of a type the person may not list
 * refused as that list is, and a reading of no type counted over the types the person may list.
 */
class InterpretTest {

    private final FakeSearchIndex index = new FakeSearchIndex();
    private final Search search = SearchFixtures.search(index);

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
        index.reading = (q, type) -> json("{\"q\":\"type:" + type + "\",\"type\":\"" + type + "\",\"filter\":{},\"explanation\":\"x\","
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
        index.reading = (q, type) -> json("{\"q\":\"type:database\",\"type\":\"database\",\"filter\":{},\"explanation\":\"Databases.\","
                + "\"confidence\":\"high\",\"total\":5}");
        DeniedException refused = assertThrows(DeniedException.class, () -> search.interpret(session("lena", COMPUTE), "databases", null).block());
        assertEquals("data:list", refused.denial().action());
        assertEquals(5, search.interpret(session("mia", COMPUTE), "databases", null).block().path("total").asInt());
    }

    @Test
    void aReadingOfNoTypeIsCountedOverTheTypesThePersonMayList() {
        index.reading = (q, type) -> json("{\"q\":\"state:failed tag:env web\",\"filter\":{\"q\":\"web\",\"state\":\"failed\",\"drift\":false,"
                + "\"region\":\"global\",\"tags\":{\"env\":\"\"}},\"explanation\":\"Resources that failed.\",\"confidence\":\"medium\",\"total\":9}");
        index.groups = query -> json("{\"total\":9,\"byState\":{},\"drift\":0,\"cappedAt\":null,\"groups\":[{\"key\":\"database\",\"total\":5},"
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
        index.reading = (q, type) -> json("{\"q\":\"\",\"filter\":{},\"explanation\":\"Resources.\",\"confidence\":\"medium\",\"total\":0}");
        InvalidInputException blank = assertThrows(InvalidInputException.class, () -> search.interpret(session("mia", COMPUTE), "  ", null).block());
        assertEquals("/q", blank.problems().get(0).field());
        InvalidInputException longer = assertThrows(InvalidInputException.class,
                () -> search.interpret(session("mia", COMPUTE), "x".repeat(501), null).block());
        assertEquals("length", longer.problems().get(0).code());
        assertEquals(0, search.interpret(session("mia", COMPUTE), "x".repeat(500), null).block().path("total").asInt());
    }
}
