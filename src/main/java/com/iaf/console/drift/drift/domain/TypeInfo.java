package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the drift service keeps of a platform resource type (P-06): the service, how its truth is
 * read, which fields are compared, ignored or mutable, and whether its provider lists.
 */
public record TypeInfo(
        String name,
        String plural,
        String service,
        String provider,
        String action,
        Map<String, String> map,
        Set<String> ignore,
        Duration interval,
        String notFound,
        boolean hasList,
        boolean hasDiscover,
        Set<String> mutable,
        List<String> fields) {

    public TypeInfo {
        map = map == null ? Map.of() : Map.copyOf(map);
        ignore = ignore == null ? Set.of() : Set.copyOf(ignore);
        mutable = mutable == null ? Set.of() : Set.copyOf(mutable);
        fields = fields == null ? List.of() : List.copyOf(fields);
        notFound = notFound == null ? "missing" : notFound;
    }

    /** Whether the platform can read this type's truth at all. */
    public boolean observable() {
        return provider != null && action != null;
    }

    /** Whether the platform answers {@code :observe-list} for it (a list action, or discover's standing in, P-09). */
    public boolean pageable() {
        return hasList || hasDiscover;
    }

    public boolean notFoundMeansDeleted() {
        return "deleted".equals(notFound);
    }
}
