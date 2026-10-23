package com.iaf.console.bff.preferences.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PreferencesTest {

    @Test
    void validatedFillsTheDefaultsAndStampsTheVersion() {
        Preferences next = new Preferences(null, null, "global", List.of("compute/vm"), Map.of("compute/vm", List.of("name")), 0).validated(1);
        assertEquals("system", next.theme());
        assertEquals("comfortable", next.density());
        assertEquals(1, next.version());
        assertEquals(List.of("compute/vm"), next.pinnedTypes());
    }

    @Test
    void wrongValuesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Preferences("neon", null, null, null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, "dense", null, null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, null, "Not A Region", null, null, 0).validated(1));
        assertThrows(IllegalArgumentException.class, () -> new Preferences(null, null, null, java.util.Collections.nCopies(51, "x/y"), null, 0).validated(1));
    }
}
