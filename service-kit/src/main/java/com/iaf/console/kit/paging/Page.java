package com.iaf.console.kit.paging;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One page of a list: its items and the opaque cursor of the next page, absent on the last one. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Page<T>(List<T> items, String next) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * A page from a query that fetched one item more than the limit: the extra item proves there is
     * a next page, whose cursor is taken from the last item kept.
     */
    public static <T> Page<T> from(List<T> fetched, int limit, Function<T, Cursor> cursorOf) {
        if (fetched.size() <= limit) {
            return new Page<>(fetched, null);
        }
        List<T> kept = fetched.subList(0, limit);
        return new Page<>(kept, CursorPaging.encode(cursorOf.apply(kept.get(limit - 1))));
    }

    public static <T> Page<T> last(List<T> items) {
        return new Page<>(items, null);
    }

    public boolean hasNext() {
        return next != null;
    }

    public <R> Page<R> map(Function<T, R> f) {
        return new Page<>(items.stream().map(f).toList(), next);
    }
}
