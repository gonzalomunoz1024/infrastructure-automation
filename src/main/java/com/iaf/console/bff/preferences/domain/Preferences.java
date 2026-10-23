package com.iaf.console.bff.preferences.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A user's console preferences: theme, density, default region, pinned types and chosen columns per type. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Preferences(String theme, String density, String defaultRegion, List<String> pinnedTypes, Map<String, List<String>> columns, Integer version) {

    public static final Set<String> THEMES = Set.of("system", "light", "dark");
    public static final Set<String> DENSITIES = Set.of("comfortable", "compact");

    public Preferences {
        pinnedTypes = pinnedTypes == null ? List.of() : List.copyOf(pinnedTypes);
        columns = columns == null ? Map.of() : Map.copyOf(columns);
    }

    public static Preferences defaults() {
        return new Preferences("system", "comfortable", null, List.of(), Map.of(), 0);
    }

    /** The same preferences checked and stamped with the next version. */
    public Preferences validated(int nextVersion) {
        if (theme != null && !THEMES.contains(theme)) {
            throw new IllegalArgumentException("theme is one of " + THEMES);
        }
        if (density != null && !DENSITIES.contains(density)) {
            throw new IllegalArgumentException("density is one of " + DENSITIES);
        }
        if (defaultRegion != null && !defaultRegion.matches("^[a-z0-9-]{2,32}$")) {
            throw new IllegalArgumentException("defaultRegion is 2 to 32 lowercase letters, digits or dashes");
        }
        if (pinnedTypes.size() > 50) {
            throw new IllegalArgumentException("at most 50 pinned types");
        }
        for (Map.Entry<String, List<String>> e : columns.entrySet()) {
            if (e.getValue() == null || e.getValue().size() > 40) {
                throw new IllegalArgumentException("at most 40 columns per type");
            }
        }
        return new Preferences(theme == null ? "system" : theme, density == null ? "comfortable" : density, defaultRegion, pinnedTypes, columns, nextVersion);
    }
}
