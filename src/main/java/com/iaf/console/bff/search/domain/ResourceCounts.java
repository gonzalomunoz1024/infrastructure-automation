package com.iaf.console.bff.search.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How many resources a list's filters match: {@code total} (the resources the list would page
 * through), {@code byState} for every lifecycle state whatever the {@code state} filter, the
 * number with an open drift finding, and {@code cappedAt} when counting stopped at a cap (the
 * console then shows "1,000+"), {@code null} when the counts are exact.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResourceCounts(long total, Map<String, Long> byState, long drift, Integer cappedAt) {

    /** The lifecycle states, in the order the console shows them. */
    public static final List<String> STATES = List.of("pending", "active", "updating", "deleting", "deleted", "failed");

    public ResourceCounts {
        Map<String, Long> all = new LinkedHashMap<>();
        for (String state : STATES) {
            Long n = byState == null ? null : byState.get(state);
            all.put(state, n == null ? 0L : n);
        }
        byState = java.util.Collections.unmodifiableMap(all);
    }
}
