package com.iaf.console.kit.paging;

/**
 * Where a page ended: the sort value and the id of its last item, so the next page starts
 * strictly after it whatever was inserted meanwhile.
 */
public record Cursor(Object sortValue, String id) {

    public Cursor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("a cursor needs the id of the last item");
        }
    }

    /** The sort value as a long, for cursors over epoch nanoseconds or sequences. */
    public long sortLong() {
        if (sortValue instanceof Number n) {
            return n.longValue();
        }
        throw new IllegalArgumentException("the cursor's sort value is not a number");
    }

    /** The sort value as text, for cursors over names. */
    public String sortText() {
        return sortValue == null ? null : sortValue.toString();
    }
}
