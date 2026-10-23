package com.iaf.console.bff.demo.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.demo.search.domain.Typos;

/** Typos as the indexer forgives them: inserts, deletes, changes and swaps, by the word's length, never the first letter. */
class TyposTest {

    @Test
    void eachEditCountsOne() {
        assertEquals(0, Typos.distance("postgres", "postgres"));
        assertEquals(1, Typos.distance("postgress", "postgres"), "an insert");
        assertEquals(1, Typos.distance("postgre", "postgres"), "a delete");
        assertEquals(1, Typos.distance("postgrez", "postgres"), "a change");
        assertEquals(1, Typos.distance("psotgres", "postgres"), "a swap of two neighbours");
        assertEquals(2, Typos.distance("pstgrse", "postgres"));
    }

    @Test
    void longerWordsForgiveMoreAndTheFirstLetterIsAsTyped() {
        assertEquals(0, Typos.allowed("web"));
        assertEquals(1, Typos.allowed("webs"));
        assertEquals(1, Typos.allowed("server"));
        assertEquals(2, Typos.allowed("servers"));
        assertTrue(Typos.within("postgress", "postgres", 2));
        assertFalse(Typos.within("oostgres", "postgres", 2), "the first letter is never forgiven");
        assertFalse(Typos.within("pstgrse", "postgres", 1));
        assertFalse(Typos.within("vm", "", 1));
    }
}
