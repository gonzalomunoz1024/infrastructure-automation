package com.iaf.console.bff.demo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.demo.search.domain.DemoQuery;
import com.iaf.console.kit.errors.InvalidInputException;

/** The demo index reads the indexer's query syntax: words, phrases and the five kinds of term. */
class DemoQueryTest {

    @Test
    void wordsPhrasesAndTerms() {
        DemoQuery q = DemoQuery.parse("Web \"blue green\" tag:env=prod tag:owner type:vm state:ACTIVE region:global drift:true wfrn:wf:compute:*");
        assertEquals(List.of("web", "blue green"), q.words());
        assertEquals(Map.of("env", "prod"), q.tags());
        assertEquals(Set.of("owner"), q.tagNames());
        assertEquals("vm", q.type());
        assertEquals("active", q.state());
        assertEquals("global", q.region());
        assertEquals(Boolean.TRUE, q.drift());
        assertEquals(List.of("wfrn:wf:compute:*"), q.wfrns());
    }

    @Test
    void quotesInsideATermOnlyGroup() {
        DemoQuery q = DemoQuery.parse("tag:team=\"data platform\" https://x");
        assertEquals(Map.of("team", "data platform"), q.tags());
        assertEquals(List.of("https://x"), q.words(), "an unknown prefix is a word");
    }

    @Test
    void aTermWithAValueTheIndexerRefusesIs400() {
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("state:sideways"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("drift:maybe"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("type:"));
        assertThrows(InvalidInputException.class, () -> DemoQuery.parse("tag:=x"));
        assertEquals(0, DemoQuery.parse("  ").words().size());
    }
}
