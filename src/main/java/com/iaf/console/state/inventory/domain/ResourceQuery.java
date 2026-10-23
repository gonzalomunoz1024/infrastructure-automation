package com.iaf.console.state.inventory.domain;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.kit.paging.CursorPaging;

/**
 * A list query over an account's resources, the filters of {@code GET /v1/resources} plus a sort:
 * {@code updatedAt} (newest first by default), {@code createdAt} or {@code name}, each ascending
 * with a plain name and descending with a leading minus. Deleted resources are left out unless
 * {@code state} asks for them.
 */
public record ResourceQuery(
        String type,
        ResourceState state,
        String namePrefix,
        Map<String, String> tags,
        String wfrnPattern,
        Instant updatedAfter,
        Boolean drift,
        Sort sort,
        int limit,
        Cursor after) {

    /** The most items one page may hold, the contract's maximum. */
    public static final int MAX_LIMIT = CursorPaging.MAX_PAGE_SIZE;
    /** The page size when the caller names none. */
    public static final int DEFAULT_LIMIT = 50;

    public ResourceQuery {
        tags = tags == null ? Map.of() : Map.copyOf(new TreeMap<>(tags));
        sort = sort == null ? Sort.UPDATED_DESC : sort;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is 1 to " + MAX_LIMIT);
        }
    }

    public static ResourceQuery all() {
        return new ResourceQuery(null, null, null, null, null, null, null, null, DEFAULT_LIMIT, null);
    }

    public ResourceQuery withType(String t) {
        return new ResourceQuery(t, state, namePrefix, tags, wfrnPattern, updatedAfter, drift, sort, limit, after);
    }

    public ResourceQuery withState(ResourceState s) {
        return new ResourceQuery(type, s, namePrefix, tags, wfrnPattern, updatedAfter, drift, sort, limit, after);
    }

    public ResourceQuery withNamePrefix(String p) {
        return new ResourceQuery(type, state, p, tags, wfrnPattern, updatedAfter, drift, sort, limit, after);
    }

    public ResourceQuery withTags(Map<String, String> t) {
        return new ResourceQuery(type, state, namePrefix, t, wfrnPattern, updatedAfter, drift, sort, limit, after);
    }

    public ResourceQuery withWfrnPattern(String p) {
        return new ResourceQuery(type, state, namePrefix, tags, p, updatedAfter, drift, sort, limit, after);
    }

    public ResourceQuery withUpdatedAfter(Instant t) {
        return new ResourceQuery(type, state, namePrefix, tags, wfrnPattern, t, drift, sort, limit, after);
    }

    public ResourceQuery withDrift(Boolean d) {
        return new ResourceQuery(type, state, namePrefix, tags, wfrnPattern, updatedAfter, d, sort, limit, after);
    }

    public ResourceQuery withSort(Sort s) {
        return new ResourceQuery(type, state, namePrefix, tags, wfrnPattern, updatedAfter, drift, s, limit, after);
    }

    public ResourceQuery withLimit(int l) {
        return new ResourceQuery(type, state, namePrefix, tags, wfrnPattern, updatedAfter, drift, sort, l, after);
    }

    public ResourceQuery withAfter(Cursor c) {
        return new ResourceQuery(type, state, namePrefix, tags, wfrnPattern, updatedAfter, drift, sort, limit, c);
    }

    /** The page size a caller asked for: the default when absent, 1 to {@value #MAX_LIMIT}. */
    public static int limitOf(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is 1 to " + MAX_LIMIT);
        }
        return requested;
    }

    /** The sorts a list takes. */
    public enum Sort {
        UPDATED_DESC("-updatedAt", "updatedAt", false),
        UPDATED_ASC("updatedAt", "updatedAt", true),
        CREATED_DESC("-createdAt", "createdAt", false),
        CREATED_ASC("createdAt", "createdAt", true),
        NAME_ASC("name", "name", true),
        NAME_DESC("-name", "name", false);

        private final String wire;
        private final String field;
        private final boolean ascending;

        Sort(String wire, String field, boolean ascending) {
            this.wire = wire;
            this.field = field;
            this.ascending = ascending;
        }

        public String wire() {
            return wire;
        }

        public String field() {
            return field;
        }

        public boolean ascending() {
            return ascending;
        }

        /** The sort a string names; an IllegalArgumentException (a 400) for one this service does not offer. */
        public static Sort of(String wire) {
            if (wire == null || wire.isBlank()) {
                return UPDATED_DESC;
            }
            for (Sort s : values()) {
                if (s.wire.equals(wire.trim())) {
                    return s;
                }
            }
            throw new IllegalArgumentException("sort is one of: -updatedAt, updatedAt, -createdAt, createdAt, name, -name");
        }

        /** The value of the sort field on a resource, as a cursor carries it: epoch nanoseconds or the name. */
        public Object valueOf(Resource r) {
            return switch (this) {
                case UPDATED_DESC, UPDATED_ASC -> nanos(r.updatedAt());
                case CREATED_DESC, CREATED_ASC -> nanos(r.createdAt());
                case NAME_ASC, NAME_DESC -> r.name() == null ? "" : r.name();
            };
        }

        private static long nanos(Instant at) {
            return at == null ? 0 : Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
        }
    }
}
