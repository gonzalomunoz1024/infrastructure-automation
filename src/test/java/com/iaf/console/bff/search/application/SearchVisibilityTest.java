package com.iaf.console.bff.search.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.iaf.console.bff.search.domain.Suggestion;

/** Which suggestions a person sees: those of the types they may list, and tag terms when they may list any type. */
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
}
