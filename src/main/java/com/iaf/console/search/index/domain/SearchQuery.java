package com.iaf.console.search.index.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.iaf.console.kit.paging.Cursor;

/**
 * A search as the engine runs it: the free-text terms, the filters (from the query's own
 * {@code type:}, {@code state:}, {@code region:}, {@code tag:}, {@code drift:}, {@code created:}
 * and {@code updated:} terms and the request's parameters, which only narrow further), the wfrn
 * patterns, the sort and the page.
 */
public record SearchQuery(
        List<String> terms,
        String type,
        ResourceState state,
        String region,
        Map<String, String> tags,
        Boolean drift,
        TimeRange created,
        TimeRange updated,
        List<String> wfrnPatterns,
        Sort sort,
        int limit,
        Cursor after,
        boolean includeDeleted) {

    public SearchQuery {
        terms = terms == null ? List.of() : List.copyOf(terms);
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
        created = created == null ? TimeRange.ALL : created;
        updated = updated == null ? TimeRange.ALL : updated;
        wfrnPatterns = wfrnPatterns == null ? List.of() : List.copyOf(wfrnPatterns);
        sort = Objects.requireNonNullElse(sort, Sort.SCORE);
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
    }

    /**
     * The orders a list offers: by relevance (the default), by name, by creation and by last change,
     * each ascending or descending ({@code -}). Ties go by wfrn, which every cursor carries, so a walk
     * never repeats or skips a resource.
     */
    public enum Sort {
        SCORE("score", Key.SCORE, true),
        NAME("name", Key.NAME, false),
        NAME_DESC("-name", Key.NAME, true),
        CREATED("created", Key.CREATED, false),
        CREATED_DESC("-created", Key.CREATED, true),
        UPDATED("updated", Key.UPDATED, false),
        UPDATED_DESC("-updated", Key.UPDATED, true);

        /** What a sort orders by, and so what its cursors carry: a number for the score and the times, the lowercased name as text. */
        public enum Key {
            SCORE, NAME, CREATED, UPDATED
        }

        private final String wire;
        private final Key key;
        private final boolean descending;

        Sort(String wire, Key key, boolean descending) {
            this.wire = wire;
            this.key = key;
            this.descending = descending;
        }

        public String wire() {
            return wire;
        }

        public Key key() {
            return key;
        }

        public boolean descending() {
            return descending;
        }

        /** Whether a cursor's sort value can be one of this sort's: text for the names, a number otherwise. */
        public boolean accepts(Object sortValue) {
            return key == Key.NAME ? sortValue instanceof String : sortValue instanceof Number;
        }

        public static Sort of(String s) {
            if (s == null || s.isBlank()) {
                return SCORE;
            }
            for (Sort v : values()) {
                if (v.wire.equals(s.trim())) {
                    return v;
                }
            }
            throw new IllegalArgumentException("sort is one of: " + names());
        }

        public static String names() {
            return "score, name, -name, created, -created, updated, -updated";
        }
    }

    /** True when the query matches by text at all; without terms every document of the filters matches. */
    public boolean hasTerms() {
        return !terms.isEmpty();
    }

    /** The free text as one line, for logs and highlights. */
    public String text() {
        return String.join(" ", terms);
    }
}
