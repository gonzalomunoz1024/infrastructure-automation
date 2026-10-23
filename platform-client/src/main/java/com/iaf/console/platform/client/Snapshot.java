package com.iaf.console.platform.client;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One page of the P-01 snapshot: the records in {@code (updatedNs, _id)} order, the cursor of the
 * next page, and the outbox sequence the feed continues from once the pages are read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Snapshot<T>(List<T> items, String next, long highWaterSeq) {

    public Snapshot {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasNext() {
        return next != null && !next.isBlank();
    }
}
