package com.iaf.console.search.index.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How many resources a list shows, for its header and its state chips. {@code total} and
 * {@code drift} apply every filter, and leave tombstones out as the list does (unless the query
 * asks for {@code state=deleted} or names one wfrn); {@code byState} applies every filter but the
 * state, tombstones included, so each chip keeps its number while one is picked. Every state is
 * present, at zero when nothing is in it. The counts are exact (an account's documents live on one
 * shard and an aggregation sees every match), so {@code cappedAt}, the cap a count would have
 * stopped at, is null. {@code groups}, when the call asks for them ({@link GroupBy}), are the same
 * counts per key, the largest first: every key that holds a document in any state, so a group's
 * {@code total} may be zero while its chips are not.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResourceCounts(long total, Map<String, Long> byState, long drift, Integer cappedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<CountGroup> groups) {

    public ResourceCounts {
        byState = everyState(byState);
        groups = groups == null ? null : List.copyOf(groups);
    }

    public ResourceCounts(long total, Map<String, Long> byState, long drift, Integer cappedAt) {
        this(total, byState, drift, cappedAt, null);
    }

    public static ResourceCounts exact(long total, Map<String, Long> byState, long drift) {
        return new ResourceCounts(total, byState, drift, null, null);
    }

    /** Every state in its order, at zero when absent. */
    static Map<String, Long> everyState(Map<String, Long> byState) {
        Map<String, Long> all = new LinkedHashMap<>();
        for (ResourceState s : ResourceState.values()) {
            Long n = byState == null ? null : byState.get(s.wire());
            all.put(s.wire(), n == null ? 0L : n);
        }
        return Collections.unmodifiableMap(all);
    }

    public ResourceCounts withGroups(List<CountGroup> groups) {
        return new ResourceCounts(total, byState, drift, cappedAt, groups);
    }

    /** The counts of two accounts added up, their groups by key. */
    public ResourceCounts plus(ResourceCounts other) {
        Map<String, Long> states = new LinkedHashMap<>(byState);
        other.byState.forEach((s, n) -> states.merge(s, n, Long::sum));
        List<CountGroup> merged = null;
        if (groups != null || other.groups != null) {
            Map<String, CountGroup> byKey = new LinkedHashMap<>();
            for (List<CountGroup> list : List.of(groups == null ? List.<CountGroup>of() : groups, other.groups == null ? List.<CountGroup>of() : other.groups)) {
                list.forEach(g -> byKey.merge(g.key(), g, CountGroup::plus));
            }
            merged = new ArrayList<>(byKey.values());
        }
        return new ResourceCounts(total + other.total, states, drift + other.drift, null, merged);
    }

    /** The counts with only their largest groups, and only groups that hold a document. */
    public ResourceCounts largest(int n) {
        if (groups == null) {
            return this;
        }
        return withGroups(groups.stream().filter(CountGroup::holdsAny).sorted(CountGroup.LARGEST_FIRST).limit(n).toList());
    }
}
