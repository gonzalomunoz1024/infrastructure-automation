package com.iaf.console.bff.preferences.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PreferencesTest {

    @Test
    void validatedFillsTheDefaultsAndStampsTheVersion() {
        Preferences next = new Preferences(null, null, "global", List.of("compute/vm"), Map.of("compute/vm", List.of("name")), null, 0).validated(1);
        assertEquals("system", next.theme());
        assertEquals("comfortable", next.density());
        assertEquals(1, next.version());
        assertEquals(List.of("compute/vm"), next.pinnedTypes());
    }

    @Test
    void wrongValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Preferences("neon", null, null, null, null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, "dense", null, null, null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, null, "Not A Region", null, null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, null, null, java.util.Collections.nCopies(51, "x/y"), null, null, 0).validated(1));
    }

    private static final Instant AT = Instant.parse("2026-10-23T12:00:00Z");

    private static SavedView view(String id, String name) {
        return new SavedView(id, name, List.of(new SavedView.Filter("state", "=", "active"), new SavedView.Filter(null, ":", "web")), "-created", 50, AT);
    }

    private static Preferences withViews(Map<String, List<SavedView>> views) {
        return new Preferences(null, null, null, null, null, views, 0);
    }

    @Test
    void savedViewsAreKeptAsSentPerTypeKey() {
        Preferences next = withViews(Map.of("compute/vm", List.of(view("v_01", "Running web"), view("v_02", "Failed")))).validated(1);
        assertEquals(2, next.savedViews().get("compute/vm").size());
        SavedView first = next.savedViews().get("compute/vm").get(0);
        assertEquals("Running web", first.name());
        assertEquals(null, first.filters().get(1).property(), "a null property is free text");
        assertEquals("-created", first.sort());
    }

    @Test
    void savedViewsTheConsoleCouldNotShowAgainAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("vm", List.of(view("v_01", "A")))).validated(1), "the key is service/type");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(view("01", "A")))).validated(1), "ids start with v_");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(view("v_01", " ")))).validated(1), "a name is needed");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(view("v_01", "x".repeat(81))))).validated(1));
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(view("v_01", "Same"), view("v_02", "same ")))).validated(1),
                "names are unique per type, ignoring case");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(view("v_01", "A"), view("v_01", "B")))).validated(1));
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(new SavedView("v_01", "A",
                List.of(new SavedView.Filter("state", "~", "x")), null, null, AT)))).validated(1), "unknown operator");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(new SavedView("v_01", "A", List.of(), null, 201, AT))))
                .validated(1), "a page holds at most 200 rows");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(new SavedView("v_01", "A", List.of(), "-", null, AT))))
                .validated(1), "a sort names a column");
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", List.of(new SavedView("v_01", "A", List.of(), null, null, null))))
                .validated(1), "createdAt is required");
        List<SavedView> many = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add(view("v_" + i, "View " + i));
        }
        assertThrows(IllegalArgumentException.class, () -> withViews(Map.of("compute/vm", many)).validated(1), "at most 50 views per type");
        assertEquals(50, withViews(Map.of("compute/vm", many.subList(0, 50))).validated(1).savedViews().get("compute/vm").size());
        assertEquals(Collections.emptyMap(), withViews(null).validated(1).savedViews());
    }
}
