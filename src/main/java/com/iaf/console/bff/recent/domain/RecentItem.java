package com.iaf.console.bff.recent.domain;

import java.time.Instant;
import java.util.Objects;

/** A page the person opened lately (decision 32.3): a type by its type key ({@code compute/vm}) or a resource by its wfrn, with its title and when. */
public record RecentItem(String kind, String key, String title, Instant visitedAt) {

    public static final String TYPE = "type";
    public static final String RESOURCE = "resource";

    public RecentItem {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(visitedAt, "visitedAt");
    }

    /** Whether two items are the same page. */
    public boolean samePage(RecentItem other) {
        return kind.equals(other.kind) && key.equals(other.key);
    }
}
