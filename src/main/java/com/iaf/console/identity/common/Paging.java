package com.iaf.console.identity.common;

/** The contract's page sizes: 1 to 200, 50 by default, the plan's cap for every console API (the kit's {@code CursorPaging}). */
public final class Paging {

    public static final int MAX = com.iaf.console.kit.paging.CursorPaging.MAX_PAGE_SIZE;
    public static final int DEFAULT = com.iaf.console.kit.paging.CursorPaging.DEFAULT_PAGE_SIZE;

    private Paging() {
    }

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        return Math.min(requested, MAX);
    }
}
