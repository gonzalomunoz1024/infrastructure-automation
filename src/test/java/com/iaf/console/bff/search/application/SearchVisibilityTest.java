package com.iaf.console.bff.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.search.domain.SearchHit;
import com.iaf.console.bff.search.domain.Suggestion;

/**
 * Which suggestions a person sees: those of the types they may list, and tag terms when they may
 * list any type; and the indexer's did-you-mean only when the hits they see spell it.
 */
class SearchVisibilityTest {

    private static final Set<String> VMS_ONLY = Set.of("vm");
    private static final String VM = "wfrn:wf:compute:global:123456789012:vm/web-01";
    private static final String BUCKET = "wfrn:wf:storage:global:123456789012:bucket/logs";

    @Test
    void aResourceOrWfrnShowsWhenItsTypeIsListable() {
        assertTrue(Search.visible(new Suggestion(Suggestion.RESOURCE, VM, "web-01", "vm", VM), VMS_ONLY));
        assertFalse(Search.visible(new Suggestion(Suggestion.RESOURCE, BUCKET, "logs", "bucket", BUCKET), VMS_ONLY));
        assertTrue(Search.visible(new Suggestion(Suggestion.WFRN, VM, VM, null, null), VMS_ONLY), "the type is read from the wfrn");
        assertFalse(Search.visible(new Suggestion(Suggestion.WFRN, BUCKET, BUCKET, null, null), VMS_ONLY));
        assertFalse(Search.visible(new Suggestion(Suggestion.WFRN, "not-a-wfrn", "x", null, null), VMS_ONLY));
    }

    @Test
    void aTypeShowsWhenListableAndATagTermAlways() {
        assertTrue(Search.visible(new Suggestion(Suggestion.TYPE, "vm", "vm", null, null), VMS_ONLY));
        assertFalse(Search.visible(new Suggestion(Suggestion.TYPE, "bucket", "bucket", "bucket", null), VMS_ONLY));
        assertTrue(Search.visible(new Suggestion(Suggestion.TAG, "tag:env=prod", "env = prod", null, null), VMS_ONLY));
        assertFalse(Search.visible(new Suggestion("something-new", "x", "x", "vm", null), VMS_ONLY), "an unknown kind is left out");
        assertFalse(Search.visible(null, VMS_ONLY));
    }

    @Test
    void didYouMeanPassesWhenEveryHitIsShown() {
        List<SearchHit> shown = List.of(hit("orders-postgres", Map.of()));
        assertEquals("postgres prod", Search.didYouMean("postgress prod", "postgres prod", shown, false));
        assertEquals("postgres prod", Search.didYouMean("postgress prod", "postgres prod", List.of(), false), "the indexer read it from what was shown");
        assertNull(Search.didYouMean("postgress", null, shown, false));
        assertNull(Search.didYouMean("postgress", " ", shown, false));
    }

    @Test
    void whenHitsWereLeftOutEveryChangedWordMustBeInAShownHit() {
        List<SearchHit> shown = List.of(hit("orders-postgres", Map.of("team", "payments")));
        assertEquals("postgres prod", Search.didYouMean("postgress prod", "postgres prod", shown, true), "spelled by the name");
        assertEquals("payments", Search.didYouMean("paymets", "payments", shown, true), "spelled by a tag");
        assertNull(Search.didYouMean("secrte", "secret", shown, true), "it could only come from a hit left out");
        assertNull(Search.didYouMean("postgress prod", "postgres", shown, true), "the words do not line up");
        assertNull(Search.didYouMean("postgress", "postgres", List.of(), true));
    }

    private static SearchHit hit(String name, Map<String, String> tags) {
        return new SearchHit("wfrn:wf:data:global:123456789012:database/" + name, "database", "data", "global", name, "active", false, tags, 1.0,
                Map.of(), null);
    }
}
