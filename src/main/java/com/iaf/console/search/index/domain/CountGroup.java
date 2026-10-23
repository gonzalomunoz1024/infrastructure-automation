package com.iaf.console.search.index.domain;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One group of a list's counts ({@link GroupBy}): its key and the same three numbers as the whole
 * list ({@link ResourceCounts}), so a node of the resource map shows its own state chips and drift.
 * A group of types carries the type's service.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CountGroup(String key, long total, Map<String, Long> byState, long drift, @JsonInclude(JsonInclude.Include.NON_NULL) String service) {

    /** The largest group first, then by key. */
    public static final Comparator<CountGroup> LARGEST_FIRST = Comparator.comparingLong(CountGroup::total).reversed()
            .thenComparing(CountGroup::key);

    public CountGroup {
        byState = ResourceCounts.everyState(byState);
    }

    /** A group of a whole list's counts, an account's when grouping by account. */
    public static CountGroup of(String key, ResourceCounts counts) {
        return new CountGroup(key, counts.total(), counts.byState(), counts.drift(), null);
    }

    /** Whether any document is in the group, in any state. */
    public boolean holdsAny() {
        return byState.values().stream().anyMatch(n -> n > 0);
    }

    /** Two counts of the same key, from two accounts, added up. */
    public CountGroup plus(CountGroup other) {
        Map<String, Long> states = new LinkedHashMap<>(byState);
        other.byState.forEach((s, n) -> states.merge(s, n, Long::sum));
        return new CountGroup(key, total + other.total, states, drift + other.drift, service != null ? service : other.service);
    }
}
