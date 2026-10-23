package com.iaf.console.bff.preferences.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A user's console preferences: theme, density, default region, pinned types, and per type key
 * ({@code service/type}) the chosen columns and the saved list views.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Preferences(String theme, String density, String defaultRegion, List<String> pinnedTypes, Map<String, List<String>> columns,
        Map<String, List<SavedView>> savedViews, Integer version) {

    public static final Set<String> THEMES = Set.of("system", "light", "dark");
    public static final Set<String> DENSITIES = Set.of("comfortable", "compact");

    static final int MAX_VIEWS_PER_TYPE = 50;
    static final int MAX_TYPES_WITH_VIEWS = 200;
    private static final Pattern TYPE_KEY = Pattern.compile("^[a-z0-9-]{1,32}/[a-z0-9-]{1,64}$");

    public Preferences {
        pinnedTypes = pinnedTypes == null ? List.of() : List.copyOf(pinnedTypes);
        columns = columns == null ? Map.of() : Map.copyOf(columns);
        savedViews = savedViews == null ? Map.of() : Map.copyOf(savedViews);
    }

    public static Preferences defaults() {
        return new Preferences("system", "comfortable", null, List.of(), Map.of(), Map.of(), 0);
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
        checkSavedViews();
        return new Preferences(theme == null ? "system" : theme, density == null ? "comfortable" : density, defaultRegion, pinnedTypes, columns, savedViews,
                nextVersion);
    }

    /** At most 50 views per type key, each one well formed, with ids and names unique within the type (names ignoring case). */
    private void checkSavedViews() {
        if (savedViews.size() > MAX_TYPES_WITH_VIEWS) {
            throw new IllegalArgumentException("saved views for at most " + MAX_TYPES_WITH_VIEWS + " types");
        }
        for (Map.Entry<String, List<SavedView>> e : savedViews.entrySet()) {
            String typeKey = e.getKey();
            if (typeKey == null || !TYPE_KEY.matcher(typeKey).matches()) {
                throw new IllegalArgumentException("savedViews is keyed by type key, service/type");
            }
            List<SavedView> views = e.getValue() == null ? List.of() : e.getValue();
            if (views.size() > MAX_VIEWS_PER_TYPE) {
                throw new IllegalArgumentException("savedViews." + typeKey + ": at most " + MAX_VIEWS_PER_TYPE + " views per type");
            }
            Set<String> ids = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (SavedView view : views) {
                if (view == null) {
                    throw new IllegalArgumentException("savedViews." + typeKey + ": a view is an object");
                }
                view.check(typeKey);
                if (!ids.add(view.id())) {
                    throw new IllegalArgumentException("savedViews." + typeKey + ": the id " + view.id() + " is used twice");
                }
                if (!names.add(view.name().strip().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("savedViews." + typeKey + ": the name '" + view.name().strip() + "' is used twice");
                }
            }
        }
    }
}
