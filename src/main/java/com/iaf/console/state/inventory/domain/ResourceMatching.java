package com.iaf.console.state.inventory.domain;

import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;

import com.iaf.console.kit.paging.Cursor;
import com.iaf.console.wfrn.WfrnPattern;

/**
 * The one meaning of a {@link ResourceQuery}, for every store: which resources match, how they are
 * ordered, and where a cursor cuts. The MongoDB adapter translates the same rules into filters and
 * checks them again on what comes back, so a page is the same whatever store serves it.
 */
public final class ResourceMatching {

    private ResourceMatching() {
    }

    /** Whether a resource matches every filter of the query (its account and region are the scope's business). */
    public static boolean matches(ResourceQuery q, Resource r) {
        if (q.state() == null ? !r.live() : r.state() != q.state()) {
            return false;
        }
        if (q.type() != null && !q.type().equals(r.type())) {
            return false;
        }
        if (q.namePrefix() != null && (r.name() == null || !r.name().startsWith(q.namePrefix()))) {
            return false;
        }
        for (Map.Entry<String, String> tag : q.tags().entrySet()) {
            if (!tag.getValue().equals(r.tags().get(tag.getKey()))) {
                return false;
            }
        }
        if (q.wfrnPattern() != null && !WfrnPattern.parse(q.wfrnPattern()).matches(r.wfrn())) {
            return false;
        }
        if (q.updatedAfter() != null && (r.updatedAt() == null || !r.updatedAt().isAfter(q.updatedAfter()))) {
            return false;
        }
        if (q.drift() != null && q.drift() != (r.drift() != null)) {
            return false;
        }
        return true;
    }

    /** The order of a query: its sort, then the wfrn ascending, so the order is total and a cursor exact. */
    public static Comparator<Resource> order(ResourceQuery q) {
        ResourceQuery.Sort sort = q.sort();
        Comparator<Resource> bySort = Comparator.comparing(r -> sortKey(sort.valueOf(r)));
        if (!sort.ascending()) {
            bySort = bySort.reversed();
        }
        return bySort.thenComparing(Resource::wfrn);
    }

    /** Whether a resource comes strictly after the cursor in the query's order. */
    public static boolean after(ResourceQuery q, Resource r) {
        Cursor cursor = q.after();
        if (cursor == null) {
            return true;
        }
        ResourceQuery.Sort sort = q.sort();
        int c = sortKey(sort.valueOf(r)).compareTo(sortKey(cursor.sortValue()));
        if (!sort.ascending()) {
            c = -c;
        }
        return c > 0 || (c == 0 && r.wfrn().compareTo(cursor.id()) > 0);
    }

    /** The cursor of a resource, as a page ends on it. */
    public static Cursor cursorOf(ResourceQuery q, Resource r) {
        return new Cursor(q.sort().valueOf(r), r.wfrn());
    }

    /** A wfrn pattern as an anchored regular expression: {@code *} any run, {@code ?} one character. */
    public static Pattern regexOf(String wfrnPattern) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : wfrnPattern.toCharArray()) {
            switch (c) {
                case '*' -> sb.append(".*");
                case '?' -> sb.append('.');
                default -> sb.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    @SuppressWarnings("unchecked")
    private static Comparable<Object> sortKey(Object value) {
        if (value == null) {
            return (Comparable<Object>) (Object) "";
        }
        if (value instanceof Number n) {
            return (Comparable<Object>) (Object) Long.valueOf(n.longValue());
        }
        return (Comparable<Object>) (Object) value.toString();
    }
}
