package com.iaf.console.platform.client;

import java.util.List;

/**
 * One page of a P-05 listing: the array the platform answered and the {@code after} cursor of the
 * {@code Link: <...>; rel="next"} header, null on the last page.
 */
public record PagedList<T>(List<T> items, String next) {

    public PagedList {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasNext() {
        return next != null && !next.isBlank();
    }

    public static <T> PagedList<T> last(List<T> items) {
        return new PagedList<>(items, null);
    }
}
