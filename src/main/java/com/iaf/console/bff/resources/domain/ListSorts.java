package com.iaf.console.bff.resources.domain;

import java.util.List;
import java.util.Map;

import com.iaf.console.kit.errors.InvalidInputException;
import com.iaf.console.kit.errors.Problem;

/**
 * The list's sorts: the console sends a column with {@code -} for descending ({@code name},
 * {@code -created}, {@code updated}); the state service takes {@code name}, {@code createdAt} and
 * {@code updatedAt}, which the BFF accepts as well.
 */
public final class ListSorts {

    /** The sorts the console offers, in its form. */
    public static final List<String> OFFERED = List.of("name", "-name", "created", "-created", "updated", "-updated");

    private static final Map<String, String> TO_STATE = Map.ofEntries(
            Map.entry("name", "name"), Map.entry("-name", "-name"),
            Map.entry("created", "createdAt"), Map.entry("-created", "-createdAt"),
            Map.entry("updated", "updatedAt"), Map.entry("-updated", "-updatedAt"),
            Map.entry("createdAt", "createdAt"), Map.entry("-createdAt", "-createdAt"),
            Map.entry("updatedAt", "updatedAt"), Map.entry("-updatedAt", "-updatedAt"));

    private ListSorts() {
    }

    /** The state service's sort for the console's; {@code null} keeps the default (most recently updated first); an unknown sort is a 400. */
    public static String toState(String sort) {
        if (sort == null || sort.isBlank()) {
            return null;
        }
        String mapped = TO_STATE.get(sort.strip());
        if (mapped == null) {
            throw new InvalidInputException("unknown sort", List.of(Problem.error("enum", "/sort", "one of: " + String.join(", ", OFFERED))));
        }
        return mapped;
    }
}
