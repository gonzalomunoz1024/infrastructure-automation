package com.iaf.console.search.index.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How many resources a list shows, for its header and its state chips. {@code total} and
 * {@code drift} apply every filter, and leave tombstones out as the list does (unless the query
 * asks for {@code state=deleted} or names one wfrn); {@code byState} applies every filter but the
 * state, tombstones included, so each chip keeps its number while one is picked. Every state is
 * present, at zero when nothing is in it. The counts are exact (an account's documents live on one
 * shard and an aggregation sees every match), so {@code cappedAt}, the cap a count would have
 * stopped at, is null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ResourceCounts(long total, Map<String, Long> byState, long drift, Integer cappedAt) {

    public ResourceCounts {
        Map<String, Long> all = new LinkedHashMap<>();
        for (ResourceState s : ResourceState.values()) {
            Long n = byState == null ? null : byState.get(s.wire());
            all.put(s.wire(), n == null ? 0L : n);
        }
        byState = Collections.unmodifiableMap(all);
    }

    public static ResourceCounts exact(long total, Map<String, Long> byState, long drift) {
        return new ResourceCounts(total, byState, drift, null);
    }
}
