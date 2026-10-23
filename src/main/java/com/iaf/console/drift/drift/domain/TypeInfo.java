package com.iaf.console.drift.drift.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the drift service keeps of a platform resource type (P-06): the service, how its truth is
 * read, which fields are compared, ignored or mutable, and whether its provider lists. Monitoring
 * and rightsizing also need the type's metrics read ({@code observe.metrics}: its action and the
 * series it maps), and the numeric values its schema allows for a field (an {@code enum}), which
 * bound the sizes a resize may propose.
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
        List<String> fields,
        Metrics metrics,
        Map<String, List<Double>> enums) {

    public TypeInfo {
        map = map == null ? Map.of() : Map.copyOf(map);
        ignore = ignore == null ? Set.of() : Set.copyOf(ignore);
        mutable = mutable == null ? Set.of() : Set.copyOf(mutable);
        fields = fields == null ? List.of() : List.copyOf(fields);
        notFound = notFound == null ? "missing" : notFound;
        enums = enums == null ? Map.of() : Map.copyOf(enums);
    }

    /** A type as drift alone needs it: no metrics read and no allowed values (what the callers before monitoring build). */
    public TypeInfo(String name, String plural, String service, String provider, String action, Map<String, String> map, Set<String> ignore,
            Duration interval, String notFound, boolean hasList, boolean hasDiscover, Set<String> mutable, List<String> fields) {
        this(name, plural, service, provider, action, map, ignore, interval, notFound, hasList, hasDiscover, mutable, fields, null, null);
    }

    /**
     * The type's metrics read ({@code observe.metrics}, P-09): the provider action the platform calls
     * and the series names it maps, such as {@code cpuPercent}.
     */
    public record Metrics(String action, List<String> series) {
        public Metrics {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /** Whether the platform can read this type's metric series. */
    public boolean hasMetrics() {
        return provider != null && metrics != null && metrics.action() != null;
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
