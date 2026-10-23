package com.iaf.console.bff.search.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A grouped count as the console asks for it ({@code GET /console/v1/search:counts}), each field
 * as it came: the grouping, the scope ({@code account}, the default, or {@code organization}) and
 * the filters of a search, {@code tags} by name ({@code tag.<name>}, an empty value any value).
 */
public record CountsRequest(String groupBy, String scope, String q, String type, String state, String drift, Map<String, String> tags) {

    public CountsRequest {
        tags = tags == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(tags));
    }
}
