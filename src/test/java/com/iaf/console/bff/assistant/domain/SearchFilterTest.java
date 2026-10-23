package com.iaf.console.bff.assistant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Whatever an assistant writes as a filter is reduced to the list's own fields and values. */
class SearchFilterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aFilterKeepsWhatTheListTakes() {
        List<String> notes = new ArrayList<>();
        SearchFilter kept = new SearchFilter("  web  ", "FAILED", true, "global", Map.of("environment", "prod")).reduced(notes);
        assertEquals(new SearchFilter("web", "failed", true, "global", Map.of("environment", "prod")), kept);
        assertTrue(notes.isEmpty(), notes.toString());
    }

    @Test
    void whatTheListDoesNotTakeIsDroppedAndSaid() {
        List<String> notes = new ArrayList<>();
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("Bad Name", "x");
        tags.put("owner", "x".repeat(300));
        tags.put("team", "compute");
        SearchFilter kept = new SearchFilter("q".repeat(300), "exploded", null, "eu west", tags).reduced(notes);
        assertEquals(new SearchFilter(null, null, null, null, Map.of("team", "compute")), kept);
        assertEquals(5, notes.size(), notes.toString());
    }

    @Test
    void nothingLeftIsNoFilter() {
        List<String> notes = new ArrayList<>();
        assertNull(new SearchFilter(null, "weird", null, null, Map.of()).reduced(notes));
        assertEquals(1, notes.size());
    }

    @Test
    void moreThanTwentyTagsAreCut() {
        Map<String, String> tags = new LinkedHashMap<>();
        for (int i = 0; i < 25; i++) {
            tags.put("t" + i, "v");
        }
        List<String> notes = new ArrayList<>();
        assertEquals(20, new SearchFilter(null, null, null, null, tags).reduced(notes).tags().size());
        assertTrue(notes.contains("more than 20 tags"), notes.toString());
    }

    @Test
    void aFilterIsReadLeniently() throws Exception {
        List<String> notes = new ArrayList<>();
        SearchFilter read = SearchFilter.read(JSON.readTree("""
                {"q": 7, "state": "active", "drift": "true", "tags": {"env": "prod", "has": null, "deep": {"x": 1}}, "extra": 1}"""), notes);
        assertNull(read.q());
        assertEquals("active", read.state());
        assertEquals(Boolean.TRUE, read.drift());
        assertEquals(Map.of("env", "prod", "has", ""), read.tags());
        assertEquals(List.of("the value of tag 'deep' is not text"), notes);
        assertNull(SearchFilter.read(JSON.readTree("null"), notes));
        assertNull(SearchFilter.read(JSON.readTree("[1]"), notes));
        assertEquals("the filter is not an object", notes.get(notes.size() - 1));
    }
}
